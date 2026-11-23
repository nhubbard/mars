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
    val memory: List<ConsoleMemoryCheck> = emptyList(),
    val initialMemory: List<ConsoleMemoryCheck> = emptyList(),
    val stores: List<ConsoleStoreCheck> = emptyList(),
    val reads: List<ConsoleReadCheck> = emptyList(),
    val marsBot: ConsoleMarsBotCheck? = null,
) {
    init {
        require(name.isNotBlank()) { "Choose a non-empty case name" }
        require(instructionLimit > 0) { "Instruction limit must be positive" }
        require(memory.size <= ConsoleMemoryCheck.MAX_RANGES && memory.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES) {
            "Memory expectations exceed 64 ranges or 1 MiB per case"
        }
        require(memory.sortedBy { it.address }.zipWithNext().all { (a, b) -> a.address + (a.byteLength - 1L) < b.address }) {
            "Memory expectation ranges must not overlap"
        }
        require(initialMemory.size <= ConsoleMemoryCheck.MAX_RANGES &&
            initialMemory.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES &&
            initialMemory.sortedBy { it.address }.zipWithNext().all { (a, b) -> a.address + (a.byteLength - 1L) < b.address }) {
            "Initial memory exceeds its range or byte bounds, or its ranges overlap"
        }
        require(stores.size + reads.size <= ConsoleStoreCheck.MAX_CHECKS &&
            stores.sumOf { it.bytes.length.toLong() / 2 } <= ConsoleStoreCheck.MAX_EXPECTED_BYTES) {
            "Declare at most 64 trace checks and 65536 expected store bytes per case"
        }
        require(input.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Case input exceeds 64 KiB" }
        require(expectedOutput.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Expected output exceeds 64 KiB" }
    }
}

data class ConsoleCheckResult(val name: String, val failure: String?) {
    val passed: Boolean get() = failure == null
}

/**
 * Reuses an assembled image and the assignment DSL's execution, output and memory checks.
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
        val capturedCases = copyOf(cases).map {
            it.copy(memory = copyOf(it.memory), initialMemory = copyOf(it.initialMemory), stores = copyOf(it.stores), reads = copyOf(it.reads),
                marsBot = it.marsBot?.let { bot -> bot.copy(trail = copyOf(bot.trail)) })
        }
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
                                val bot = case.marsBot?.let { it to execution.connectMarsBot(it.instructionsPerTick) }
                                for (range in case.initialMemory) execution.setBytes(range.address, *range.decoded())
                                val stores = case.stores.map { it to execution.traceMemoryStores(it.address, it.byteLength) }
                                val reads = case.reads.map { it to execution.traceMemoryAccesses(it.address, it.byteLength) }
                                execution.runToExit()
                                bot?.let { (check, device) -> check.verify(device) }
                                execution.expectOutput(case.expectedOutput)
                                for (range in case.memory) execution.expectBytes(range.address, *range.decoded())
                                for ((check, trace) in stores) check.verify(trace)
                                for ((check, trace) in reads) check.verify(trace)
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
