package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.MemoryState
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
    val argumentAddresses: Map<String, FunctionDataAddress> = emptyMap(),
    val memory: List<FunctionMemoryCheck> = emptyList(),
    val expectedAddresses: Map<String, FunctionDataAddress> = emptyMap(),
    val addressMemory: List<FunctionAddressMemoryCheck> = emptyList(),
) {
    init {
        functionLabel(entry)
        functionLabel(returnTo)
        require(entry != returnTo) { "Function entry and continuation must differ" }
        require(arguments.keys.intersect(argumentAddresses.keys).isEmpty()) { "An argument cannot have both integer and address setup" }
        val prepared = arguments + argumentAddresses.mapValues { 0L }
        functionRegisters(prepared, writable = true)
        require(prepared.keys.none { functionRegister(it) in setOf(29, 31) }) { "Arguments cannot replace the stack or return pointer" }
        require(expectedRegisters.keys.intersect(expectedAddresses.keys).isEmpty()) { "A result cannot have both integer and address expectations" }
        val expected = expectedRegisters + expectedAddresses.mapValues { 0L }
        functionRegisters(expected, writable = false)
        functionMemory(memory)
        require(memory.size + addressMemory.size <= ConsoleMemoryCheck.MAX_RANGES &&
            memory.sumOf { it.byteLength.toLong() } + 8L * addressMemory.size <= ConsoleMemoryCheck.MAX_BYTES) {
            "Function results exceed 64 memory ranges or one MiB"
        }
        require(expected.isNotEmpty() || memory.isNotEmpty() || addressMemory.isNotEmpty()) { "Declare a register or memory function result" }
    }
}

/** A public half-open instruction interval for required or forbidden completed execution. */
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
 * Caller stack bytes are compared after every return; optional store protection also forbids writes.
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
    val initialMemory: List<FunctionMemoryCheck> = emptyList(),
    val noStores: List<FunctionNoStoreCheck> = emptyList(),
    val accessBounds: List<FunctionAccessCheck> = emptyList(),
    val protectCallerStack: Boolean = false,
    val transfers: List<FunctionTransferCheck> = emptyList(),
    val storeBounds: List<FunctionStoreBoundsCheck> = emptyList(),
    val noExecution: List<FunctionHelperCheck> = emptyList(),
    val initializedOutput: List<FunctionInitializationCheck> = emptyList(),
    val accessPolicies: List<FunctionAccessPolicyCheck> = emptyList(),
) {
    init {
        require(name.isNotBlank() && name.length <= 128) { "Choose a nonempty function case name of at most 128 characters" }
        require(calls.size in 1..512 && instructionLimit in 1..1_000_000) { "Function cases need 1 through 512 calls and a bounded instruction limit" }
        functionRegisters(initialRegisters, writable = true)
        val preserved = preservedRegisters.map(::functionRegister)
        require(preserved.size <= 32 && preserved.distinct().size == preserved.size) { "Duplicate or excessive preserved registers" }
        require(noExecution.size <= 32 && noExecution.distinct().size == noExecution.size) { "Duplicate or excessive forbidden execution intervals" }
        require(helpers.size <= 32 && helpers.distinct().size == helpers.size) { "Duplicate or excessive helper intervals" }
        require(stackAlignment in 1..4096 && stackAlignment and (stackAlignment - 1) == 0) { "Stack alignment must be a power of two through 4096" }
        require(callerStackBytes in 0..65536) { "Observe at most 65536 caller stack bytes" }
        require(transfers.size <= 32 && transfers.sumOf { 2L * it.byteLength } <= ConsoleMemoryCheck.MAX_BYTES) {
            "Function transfers exceed 32 maps or one MiB of active source/destination bytes"
        }
        require(storeBounds.size <= 64) { "Observe at most 64 assembled store windows" }
        require(initializedOutput.size <= 64 && initializedOutput.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES) {
            "Initialized output exceeds 64 ranges or one MiB per case"
        }
        require(accessPolicies.size <= 64 && accessPolicies.sumOf { policy ->
            policy.readable.sumOf { it.byteLength.toLong() } + policy.writable.sumOf { it.byteLength.toLong() }
        } <= ConsoleMemoryCheck.MAX_BYTES) { "Access policies exceed 64 watched intervals or one MiB of permitted data per case" }
        functionMemory(initialMemory)
        require(noStores.size <= ConsoleMemoryCheck.MAX_RANGES &&
            noStores.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES) {
            "Protected function regions exceed 64 ranges or one MiB per case"
        }
        require(initialMemory.sumOf { it.byteLength.toLong() } +
            calls.sumOf { call -> call.memory.sumOf { it.byteLength.toLong() } + 8L * call.addressMemory.size } <= ConsoleMemoryCheck.MAX_BYTES) {
            "Combined function setup and expected memory exceed one MiB per case"
        }
        require(accessBounds.size <= ConsoleMemoryCheck.MAX_RANGES &&
            accessBounds.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES) {
            "Function access windows exceed 64 ranges or one MiB per case"
        }
    }
}

internal fun functionLabel(label: String) {
    require(label.length <= 128 && Regex("[A-Za-z_.$][A-Za-z0-9_.$]*").matches(label)) { "Expected a public assembly label" }
}

private fun functionRegister(name: String): Int {
    require(name.isNotBlank() && name != "$") { "Unknown integer register: $name" }
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
                it.copy(arguments = copyMap(it.arguments), expectedRegisters = copyMap(it.expectedRegisters),
                    argumentAddresses = copyMap(it.argumentAddresses), memory = copyOf(it.memory), expectedAddresses = copyMap(it.expectedAddresses), addressMemory = copyOf(it.addressMemory))
            }, initialRegisters = copyMap(case.initialRegisters), preservedRegisters = copyOf(case.preservedRegisters),
                helpers = copyOf(case.helpers), initialMemory = copyOf(case.initialMemory), noStores = copyOf(case.noStores),
                accessBounds = copyOf(case.accessBounds),
                transfers = copyOf(case.transfers).map { it.copy(destinationIndices = copyOf(it.destinationIndices)) },
                storeBounds = copyOf(case.storeBounds), noExecution = copyOf(case.noExecution), initializedOutput = copyOf(case.initializedOutput),
                accessPolicies = copyOf(case.accessPolicies).map { it.copy(readable = copyOf(it.readable), writable = copyOf(it.writable)) })
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
                                evaluateCalls(execution, case, machine.memory, checkCancellation)
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

    private fun evaluateCalls(execution: AssemblyCase, case: FunctionCheckCase, memory: MemoryState, checkCancellation: () -> Unit) {
        val observations = mutableListOf<FunctionTransferObservation>()
        val initialized = mutableListOf<FunctionInitializationObservation>()
        try {
            for (transfer in case.transfers) observations += FunctionTransferObservation(memory, transfer,
                execution.readDataAddress(transfer.source.label, transfer.source.offset.toLong()),
                execution.readDataAddress(transfer.destination.label, transfer.destination.offset.toLong()))
            for (range in case.initializedOutput) initialized += FunctionInitializationObservation(memory, range,
                execution.readDataAddress(range.at.label, range.at.offset.toLong()))
            evaluateObservedCalls(execution, case, observations, initialized, memory.byteOrder, checkCancellation)
        } finally {
            observations.forEach(FunctionTransferObservation::close)
            initialized.forEach(FunctionInitializationObservation::close)
        }
    }

    private fun evaluateObservedCalls(
        execution: AssemblyCase, case: FunctionCheckCase, transfers: List<FunctionTransferObservation>,
        initialized: List<FunctionInitializationObservation>, littleEndian: Boolean, checkCancellation: () -> Unit,
    ) {
        for (range in case.initialMemory) execution.setBytes(range.label, *range.decoded(), offset = range.offset)
        for ((register, value) in case.initialRegisters) execution.setRegister(register, value)
        val top = execution.readRegister("sp") and -case.stackAlignment.toLong()
        execution.setRegister("sp", top)
        val callerBytes = execution.readBytes(top, case.callerStackBytes)
        val protected = case.noStores.map { execution.traceMemoryStores(it.label, it.byteLength, it.offset) }
        val callerStores = if (case.protectCallerStack && case.callerStackBytes > 0)
            execution.traceMemoryStores(top, case.callerStackBytes) else null
        val accessBounds = case.accessBounds.map { range ->
            Triple(execution.traceMemoryAccesses(range.label, range.byteLength, range.offset),
                execution.readDataAddress(range.label, range.permittedOffset.toLong()), range.permittedByteLength)
        }
        var watchedStoreBytes = 0L
        val storeBounds = case.storeBounds.map { range ->
            val first = execution.readDataAddress(range.from.label, range.from.offset.toLong())
            val until = execution.readDataAddress(range.until.label, range.until.offset.toLong())
            execution.expectCondition(until > first && until - first <= ConsoleMemoryCheck.MAX_BYTES,
                "Assembled store window ${range.from.label}..${range.until.label} must contain one through one MiB of bytes")
            watchedStoreBytes += until - first
            execution.expectCondition(watchedStoreBytes <= ConsoleMemoryCheck.MAX_BYTES,
                "Combined assembled store windows exceed one MiB")
            val permitted = range.permittedFrom?.let { execution.readDataAddress(it.label, it.offset.toLong()) } ?: 0L
            execution.expectCondition(permitted <= Long.MAX_VALUE - range.permittedByteLength,
                "Permitted store window overflows")
            Triple(execution.traceMemoryAccesses(first, (until - first).toInt()), permitted, range.permittedByteLength)
        }
        var watchedAccessBytes = 0L
        val accessPolicies = case.accessPolicies.map { policy ->
            val first = execution.readDataAddress(policy.from.label, policy.from.offset.toLong())
            val until = execution.readDataAddress(policy.until.label, policy.until.offset.toLong())
            execution.expectCondition(until > first && until - first <= ConsoleMemoryCheck.MAX_BYTES,
                "Assembled access interval must contain one through one MiB of bytes")
            watchedAccessBytes += until - first
            execution.expectCondition(watchedAccessBytes <= ConsoleMemoryCheck.MAX_BYTES, "Combined assembled access intervals exceed one MiB")
            fun resolve(windows: List<FunctionDataWindow>) = windows.map { window ->
                val address = execution.readDataAddress(window.at.label, window.at.offset.toLong())
                execution.expectCondition(address <= Long.MAX_VALUE - window.byteLength, "Permitted data window overflows")
                address to window.byteLength
            }
            Triple(execution.traceMemoryAccesses(first, (until - first).toInt()), resolve(policy.readable), resolve(policy.writable))
        }
        fun intervalTrace(helper: FunctionHelperCheck): ExecutionTrace {
            try {
                return execution.traceExecution(helper.from, helper.until)
            } catch (failure: IllegalArgumentException) {
                // Label addresses are known only after assembling this submission.
                throw CaseFailure("Invalid helper interval ${helper.from}..${helper.until}: ${failure.message}")
            }
        }
        val helpers = case.helpers.map(::intervalTrace)
        val forbidden = case.noExecution.map(::intervalTrace)
        for ((index, call) in case.calls.withIndex()) {
            try {
                checkCancellation()
                for ((register, value) in call.arguments) execution.setRegister(register, value)
                for ((register, address) in call.argumentAddresses) execution.setRegisterToDataAddress(register, address.label, address.offset.toLong())
                transfers.forEach(FunctionTransferObservation::beginCall)
                initialized.forEach(FunctionInitializationObservation::beginCall)
                execution.enterFunction(call.entry, call.returnTo)
                val saved = case.preservedRegisters.associateWith(execution::readRegister)
                val before = helpers.map { it.snapshot().instructions }
                val forbiddenBefore = forbidden.map { it.snapshot().instructions }
                execution.runUntil(call.returnTo)
                for ((register, value) in call.expectedRegisters) execution.expectRegister(register, value)
                for ((register, address) in call.expectedAddresses)
                    execution.expectRegister(register, execution.readDataAddress(address.label, address.offset.toLong()))
                for (range in call.memory) execution.expectBytes(range.label, *range.decoded(), offset = range.offset)
                for (range in call.addressMemory) {
                    val value = execution.readDataAddress(range.value.label, range.value.offset.toLong())
                    val bytes = IntArray(8) { byte ->
                        val shift = 8 * if (littleEndian) byte else 7 - byte
                        (value ushr shift and 255L).toInt()
                    }
                    execution.expectBytes(range.at.label, *bytes, offset = range.at.offset)
                }
                transfers.forEach(FunctionTransferObservation::expectComplete)
                initialized.forEach(FunctionInitializationObservation::expectComplete)
                protected.forEach(MemoryStoreTrace::expectNone)
                callerStores?.expectNone()
                for ((trace, first, length) in accessBounds) for (access in trace.snapshot()) {
                    execution.expectCondition(access.address >= first && access.length <= length &&
                        access.address - first <= length.toLong() - access.length,
                        "Expected accesses within $length bytes at 0x${first.toString(16)}, observed " +
                            "${access.length}-byte ${if (access.write) "store" else "read"} at 0x${access.address.toString(16)}")
                }
                for ((trace, first, length) in storeBounds) for (access in trace.snapshot()) {
                    if (!access.write) continue
                    execution.expectCondition(access.address >= first && access.length <= length &&
                        access.address - first <= length.toLong() - access.length,
                        "Expected stores within $length bytes at 0x${first.toString(16)}, observed " +
                            "${access.length}-byte store at 0x${access.address.toString(16)}")
                }
                for ((trace, readable, writable) in accessPolicies) for (access in trace.snapshot()) {
                    val windows = if (access.write) writable else readable
                    execution.expectCondition(windows.any { (first, length) ->
                        access.address >= first && access.length <= length && access.address - first <= length.toLong() - access.length
                    }, "Execution ${if (access.write) "store" else "read"} of ${access.length} bytes at 0x${access.address.toString(16)} is outside its permitted caller windows")
                }
                execution.expectRegister("sp", top)
                for ((register, value) in saved) execution.expectRegister(register, value)
                execution.expectCondition(execution.readBytes(top, case.callerStackBytes).contentEquals(callerBytes),
                    "Caller-owned stack bytes changed after call ${index + 1}")
                for ((helper, count) in helpers.zip(before)) execution.expectCondition(helper.snapshot().instructions > count,
                    "Required helper interval was not executed in call ${index + 1}")
                for ((trace, count) in forbidden.zip(forbiddenBefore)) execution.expectCondition(trace.snapshot().instructions == count,
                    "Forbidden instruction interval was executed in call ${index + 1}")
                execution.expectOutput("")
                checkCancellation()
            } catch (failure: CaseFailure) {
                val arguments = call.arguments.entries.sortedBy { it.key }
                    .joinToString(", ") { (register, value) -> "$register=$value" }
                val supplied = if (arguments.isEmpty()) "" else " ($arguments)"
                throw CaseFailure("Call ${index + 1} to ${call.entry} returning at ${call.returnTo}$supplied: ${failure.message}")
            }
        }
    }
}
