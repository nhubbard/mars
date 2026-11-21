package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.core.AssemblyImage
import edu.missouristate.mars.core.MarsContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.List.copyOf

/** Public formative cases are data; checking them does not evaluate an assignment script. */
data class ConsoleCheckCase(
    val name: String,
    val input: String,
    val expectedOutput: String,
    val instructionLimit: Long = 100_000,
) {
    init {
        require(name.isNotBlank()) { "Choose a non-empty case name" }
        require(instructionLimit > 0) { "Instruction limit must be positive" }
        require(input.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Case input exceeds 64 KiB" }
        require(expectedOutput.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Expected output exceeds 64 KiB" }
    }
}

data class ConsoleCheckResult(val name: String, val failure: String?) {
    val passed: Boolean get() = failure == null
}

/**
 * Reuses an assembled image and the assignment DSL's execution/output checks.
 * [createSession] must return a fresh caller-configured session for every case;
 * this checker owns and closes it. It never uses a student's active Run/Debug machine.
 * The existing grader bounds output and disables host files, clocks and GUI syscalls.
 * Cancellation is checked while loading and between bounded executions. The case's
 * instruction limit bounds the execution before the next cancellation check.
 */
object PreparedConsoleChecks {
    fun evaluate(
        image: AssemblyImage,
        cases: List<ConsoleCheckCase>,
        createSession: () -> MarsContext,
        checkCancellation: () -> Unit = {},
    ): List<ConsoleCheckResult> {
        val capturedCases = copyOf(cases)
        require(capturedCases.isNotEmpty()) { "Declare at least one console case" }
        require(capturedCases.map { it.name }.distinct().size == capturedCases.size) { "Case names must be unique" }
        val results = capturedCases.map { case ->
            checkCancellation()
            try {
                createSession().use { machine ->
                    machine.loadAssemblyImage(image, checkCancellation)
                    val program = checkNotNull(machine.program)
                    val captured = ByteArrayOutputStream()
                    PrintStream(BoundedOutput(captured), true, Charsets.UTF_8).use { output ->
                        SessionSyscallEnvironment(machine.memory,
                            ByteArrayInputStream(case.input.toByteArray(Charsets.UTF_8)), output, output,
                            inputCharset = Charsets.UTF_8,
                        ).use { console ->
                            program.setSyscallEnvironment(GradingEnvironment(console, GradingFiles(), GradingTiming()))
                            machine.simulator().setInstructionLimit(case.instructionLimit)
                            AssemblyCase(machine, program.symbolMap(), captured).use { execution ->
                                execution.runToExit()
                                execution.expectOutput(case.expectedOutput)
                            }
                        }
                    }
                }
                ConsoleCheckResult(case.name, null)
            } catch (failure: CaseFailure) {
                ConsoleCheckResult(case.name, failure.message)
            }.also { checkCancellation() }
        }
        return copyOf(results)
    }
}
