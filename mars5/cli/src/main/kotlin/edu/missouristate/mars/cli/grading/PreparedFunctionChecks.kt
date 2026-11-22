package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.List.copyOf
import java.util.Map.copyOf as copyMap

/** Public entry/continuation labels and full-width argument/result values; no executable predicate. */
data class FunctionCallCheck(
    val entry: String,
    val returnTo: String,
    val arguments: Map<String, Long>,
    val expectedRegisters: Map<String, Long>,
) {
    init {
        functionLabel(entry)
        functionLabel(returnTo)
        require(entry != returnTo) { "Function entry and continuation must differ" }
        functionRegisters(arguments, writable = true)
        require(arguments.keys.none { functionRegister(it) in setOf(29, 31) }) { "Arguments cannot replace the stack or return pointer" }
        functionRegisters(expectedRegisters, writable = false)
        require(expectedRegisters.isNotEmpty()) { "Declare a function result" }
    }
}

/** Require actual completed execution in a public helper's half-open instruction interval. */
data class FunctionHelperCheck(val from: String, val until: String) {
    init {
        functionLabel(from)
        functionLabel(until)
        require(from != until) { "Choose distinct helper interval labels" }
    }
}

/**
 * Repeated calls share one case's machine and cumulative instruction budget. Each case
 * gets a fresh machine. Stack alignment is caller setup before the first call, not a
 * callee action; each call must restore that pointer and its incoming preserved values.
 * Caller stack bytes are compared after every return, not claimed as a write history.
 */
data class FunctionCheckCase(
    val name: String,
    val calls: List<FunctionCallCheck>,
    val instructionLimit: Long = 100_000,
    val initialRegisters: Map<String, Long> = emptyMap(),
    val preservedRegisters: List<String> = emptyList(),
    val helpers: List<FunctionHelperCheck> = emptyList(),
    val stackAlignment: Int = 16,
    val callerStackBytes: Int = 32,
) {
    init {
        require(name.isNotBlank() && name.length <= 128) { "Choose a nonempty function case name of at most 128 characters" }
        require(calls.size in 1..512 && instructionLimit in 1..1_000_000) { "Function cases need 1 through 512 calls and a bounded instruction limit" }
        functionRegisters(initialRegisters, writable = true)
        val preserved = preservedRegisters.map(::functionRegister)
        require(preserved.size <= 32 && preserved.distinct().size == preserved.size) { "Duplicate or excessive preserved registers" }
        require(helpers.size <= 32 && helpers.distinct().size == helpers.size) { "Duplicate or excessive helper intervals" }
        require(stackAlignment in 1..4096 && stackAlignment and (stackAlignment - 1) == 0) { "Stack alignment must be a power of two through 4096" }
        require(callerStackBytes in 0..65536) { "Observe at most 65536 caller stack bytes" }
    }
}

private fun functionLabel(label: String) {
    require(label.length <= 128 && Regex("[A-Za-z_.$][A-Za-z0-9_.$]*").matches(label)) { "Expected a public assembly label" }
}

private fun functionRegister(name: String): Int {
    val spelling = if (name.startsWith('$')) name else "$$name"
    return requireNotNull(RegisterNames.integer(spelling)) { "Unknown integer register: $name" }.number
}

private fun functionRegisters(registers: Map<String, Long>, writable: Boolean) {
    val numbers = registers.keys.map(::functionRegister)
    require(numbers.size <= 32 && numbers.distinct().size == numbers.size) { "Duplicate register aliases or excessive registers" }
    require(!writable || 0 !in numbers) { "Cannot initialize the zero register" }
}

/** Data-only counterpart of the existing callFunction/expectRegister assignment DSL. */
object PreparedFunctionChecks {
    fun evaluate(
        image: AssemblyImage,
        cases: List<FunctionCheckCase>,
        createSession: () -> MarsContext,
        checkCancellation: () -> Unit = {},
    ): List<ConsoleCheckResult> {
        // Reconstruct nested values before opening any machine: caller-owned mutable
        // containers cannot change a later call or bypass constructor validation.
        val captured = copyOf(cases).map { case ->
            case.copy(calls = copyOf(case.calls).map {
                it.copy(arguments = copyMap(it.arguments), expectedRegisters = copyMap(it.expectedRegisters))
            }, initialRegisters = copyMap(case.initialRegisters), preservedRegisters = copyOf(case.preservedRegisters),
                helpers = copyOf(case.helpers))
        }
        require(captured.size in 1..256 && captured.map { it.name }.distinct().size == captured.size) { "Declare 1 through 256 uniquely named function cases" }
        require(captured.sumOf { it.calls.size.toLong() } <= 4096 && captured.sumOf { it.instructionLimit } <= 10_000_000) {
            "Function profiles exceed 4096 calls or a ten-million-instruction budget"
        }
        return copyOf(captured.map { case ->
            checkCancellation()
            try {
                createSession().use { machine ->
                    machine.loadAssemblyImage(image, checkCancellation)
                    val program = checkNotNull(machine.program)
                    val capturedOutput = ByteArrayOutputStream()
                    PrintStream(BoundedOutput(capturedOutput), true, Charsets.UTF_8).use { output ->
                        SessionSyscallEnvironment(machine.memory, ByteArrayInputStream(byteArrayOf()), output, output,
                            inputCharset = Charsets.UTF_8).use { console ->
                            program.setSyscallEnvironment(GradingEnvironment(console, GradingFiles(), GradingTiming()))
                            machine.simulator().setInstructionLimit(case.instructionLimit)
                            AssemblyCase(machine, program.symbolMap(), capturedOutput).use { execution ->
                                evaluateCalls(execution, case, checkCancellation)
                            }
                        }
                    }
                }
                ConsoleCheckResult(case.name, null)
            } catch (failure: CaseFailure) {
                ConsoleCheckResult(case.name, failure.message)
            }.also { checkCancellation() }
        })
    }

    private fun evaluateCalls(execution: AssemblyCase, case: FunctionCheckCase, checkCancellation: () -> Unit) {
        for ((register, value) in case.initialRegisters) execution.setRegister(register, value)
        val top = execution.readRegister("sp") and -case.stackAlignment.toLong()
        execution.setRegister("sp", top)
        val callerBytes = execution.readBytes(top, case.callerStackBytes)
        val helpers = case.helpers.map { helper ->
            try {
                execution.traceExecution(helper.from, helper.until)
            } catch (failure: IllegalArgumentException) {
                // Label addresses are known only after assembling this submission.
                // A reversed interval is a failed source contract, not a host error.
                throw CaseFailure("Invalid helper interval ${helper.from}..${helper.until}: ${failure.message}")
            }
        }
        for ((index, call) in case.calls.withIndex()) {
            checkCancellation()
            for ((register, value) in call.arguments) execution.setRegister(register, value)
            execution.enterFunction(call.entry, call.returnTo)
            val saved = case.preservedRegisters.associateWith(execution::readRegister)
            val before = helpers.map { it.snapshot().instructions }
            execution.runUntil(call.returnTo)
            for ((register, value) in call.expectedRegisters) execution.expectRegister(register, value)
            execution.expectRegister("sp", top)
            for ((register, value) in saved) execution.expectRegister(register, value)
            execution.expectCondition(execution.readBytes(top, case.callerStackBytes).contentEquals(callerBytes),
                "Caller-owned stack bytes changed after call ${index + 1}")
            for ((helper, count) in helpers.zip(before)) execution.expectCondition(helper.snapshot().instructions > count,
                "Required helper interval was not executed in call ${index + 1}")
            execution.expectOutput("")
            checkCancellation()
        }
    }
}
