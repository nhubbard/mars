@file:Suppress("unused")

package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.api.StopEvent.Reason
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.devices.MarsBotDevice
import edu.missouristate.mars.devices.DataCacheSimulator.Options as CacheOptions
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteOrder
import java.io.PrintStream
import java.util.concurrent.CancellationException

@DslMarker
annotation class AssignmentDsl

internal data class CaseResult(val name: String, val failure: String?)

@AssignmentDsl
class Assignment internal constructor(
    private val submission: AssemblySourceSnapshot,
) {
    internal val results = mutableListOf<CaseResult>()

    fun case(
        name: String,
        instructionLimit: Long = 100_000,
        input: String = "",
        utf8Text: Boolean = false,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        verify: AssemblyCase.() -> Unit,
    ) {
        require(name.isNotBlank() && results.none { it.name == name }) { "Choose a unique, non-empty case name" }
        require(instructionLimit > 0) { "Instruction limit must be positive" }
        try {
            DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
                machine.settings.isUtf8TextEnabled = utf8Text
                // Select before assembly so data directives and later execution agree.
                machine.memory.byteOrder = byteOrder == ByteOrder.LITTLE_ENDIAN
                var assemblyChecks = 0
                val program = machine.assembleSnapshot(submission, null, extended = true, warnings = false) {
                    if (++assemblyChecks > 1_000_000) throw AssemblyLimitReached()
                }
                val captured = ByteArrayOutputStream()
                PrintStream(BoundedOutput(captured), true, Charsets.UTF_8).use { output ->
                    SessionSyscallEnvironment(
                        machine.memory, ByteArrayInputStream(input.encodeToByteArray()), output, output,
                        inputCharset = Charsets.UTF_8,
                    ).use { console ->
                        val files = GradingFiles()
                        val timing = GradingTiming()
                        program.setSyscallEnvironment(GradingEnvironment(console, files, timing))
                        machine.simulator().setInstructionLimit(instructionLimit)
                        AssemblyCase(machine, program.symbolMap(), captured, files, timing).use { it.verify() }
                    }
                }
            }
            results += CaseResult(name, null)
        } catch (failure: CaseFailure) {
            results += CaseResult(name, failure.message)
        } catch (_: AssemblyLimitReached) {
            results += CaseResult(name, "Submission exceeds the assembly work limit")
        } catch (failure: ProcessingException) {
            results += CaseResult(name, failure.errors()?.generateErrorAndWarningReport()?.trim() ?: failure.toString())
        }
    }
}

@AssignmentDsl
class AssemblyCase internal constructor(
    private val machine: MarsContext,
    private val symbols: AssemblySymbolMap,
    private val output: ByteArrayOutputStream,
    private val files: GradingFiles = GradingFiles(),
    private val timing: GradingTiming = GradingTiming(),
) : AutoCloseable {
    private val simulator = machine.simulator()
    private var marsBot: MarsBotCheck? = null
    private val memoryStoreTraces = mutableListOf<MemoryStoreTrace>()
    private val memoryAccessTraces = mutableListOf<MemoryAccessTrace>()
    private val executionTraces = mutableListOf<ExecutionTrace>()
    private val dataCaches = mutableListOf<DataCacheCheck>()
    private var stop: StopEvent? = null
    private var error: String? = null

    init {
        simulator.addListener(object : SimulatorListener {
            override fun onError(event: ErrorEvent) {
                error = event.message
            }
            override fun onStep(event: StepEvent) = Unit
            override fun onStop(event: StopEvent) = Unit
        })
    }

    fun requireTextLabel(label: String) {
        textAddress(label)
    }

    /**
     * Enable file operations against these copied in-memory fixtures only.
     * Paths match exactly. Only [writablePaths] allow write/truncate (1) or append (9) opens;
     * other missing paths fail. No host file is opened.
     * [maxReadSize] forces short reads. A nonzero read at or beyond [failAfterBytes]
     * returns -1, including at EOF; a preceding read stops at that byte position.
     * Empty [contents] enables missing-file tests. Other cases keep file syscalls disabled.
     * [firstDescriptor] selects the lowest fixture descriptor (3..31), allowing checks
     * to expose submissions that assume every successful open returns descriptor 3.
     * [maxWriteSize] forces short writes. [failWriteAfterBytes] counts bytes written per
     * opened descriptor, including append mode, and a preceding write stops at that count.
     * [failWriteAfterBytesByPath] overrides that position for each named writable path,
     * independently of other output files. Its copied entries also count from each open.
     * [maxFileSize] bounds each output file; a write may fill the remaining space, then
     * further nonempty writes fail. Empty writes succeed on an open writable descriptor.
     * An empty [writablePaths] keeps syscall 15 disabled, including writes to stdout/stderr.
     */
    fun provideFiles(
        contents: Map<String, ByteArray>,
        maxReadSize: Int = Int.MAX_VALUE,
        failAfterBytes: Int? = null,
        firstDescriptor: Int = 3,
        writablePaths: Set<String> = emptySet(),
        maxWriteSize: Int = Int.MAX_VALUE,
        failWriteAfterBytes: Int? = null,
        maxFileSize: Int = 1024 * 1024,
        failWriteAfterBytesByPath: Map<String, Int> = emptyMap(),
    ): FileAccessCheck = files.configure(contents, maxReadSize, failAfterBytes, firstDescriptor,
        writablePaths, maxWriteSize, failWriteAfterBytes, maxFileSize, failWriteAfterBytesByPath)

    fun requireDataLabel(label: String) {
        dataAddress(label, 0, alignment = 1)
    }

    /**
     * Opt into deterministic time (30) and nonblocking sleep (32) services for this case.
     * Each time request consumes one copied [readings] value, preserving all 64 bits.
     * Sleep requests are recorded without advancing the readings or waiting on the host.
     * Empty readings permit sleep-only checks; an exhausted clock fails the case.
     * Negative sleep requests and history overflow also fail explicitly.
     */
    fun provideTiming(readings: List<Long>, maxEvents: Int = 65_536): TimingCheck =
        timing.configure(readings, maxEvents)

    /** Check a data symbol's byte address, including addresses above 4 GiB. */
    fun expectDataAddress(label: String, expected: Long) {
        val actual = dataAddress(label, 0, alignment = 1)
        requireCase(actual == expected) {
            "Expected address of data label $label = 0x${expected.toString(16)}, got 0x${actual.toString(16)}"
        }
    }

    /** Resolve an object's byte address without reading memory or emitting a simulated access. */
    fun readDataAddress(label: String, offset: Long = 0): Long = dataAddress(label, offset, alignment = 1)

    /** Observe subsequent execution stores overlapping a byte-addressed device region. */
    fun traceMemoryStores(address: Long, byteLength: Int = 1): MemoryStoreTrace =
        MemoryStoreTrace(machine.memory, address, byteLength).also(memoryStoreTraces::add)

    /** Observe stores to a data object without constraining its allocation address. */
    fun traceMemoryStores(label: String, byteLength: Int = 1, offset: Int = 0): MemoryStoreTrace =
        traceMemoryStores(dataAddress(label, offset.toLong(), alignment = 1), byteLength)

    /** Observe native data reads/writes overlapping a region, without an extra cache or device model. */
    fun traceMemoryAccesses(address: Long, byteLength: Int = 1, maxAccesses: Int = 65_536): MemoryAccessTrace =
        MemoryAccessTrace(machine.memory, address, byteLength, maxAccesses).also(memoryAccessTraces::add)

    /** Observe a byte-addressed object, retaining full widths of accesses crossing its boundaries. */
    fun traceMemoryAccesses(
        label: String,
        byteLength: Int = 1,
        offset: Int = 0,
        maxAccesses: Int = 65_536,
    ): MemoryAccessTrace = traceMemoryAccesses(dataAddress(label, offset.toLong(), alignment = 1), byteLength, maxAccesses)

    /** Count subsequent completed instructions and native branch decisions. */
    fun traceExecution(): ExecutionTrace = ExecutionTrace(simulator).also(executionTraces::add)

    /** Count from fromLabel (inclusive) to untilLabel (exclusive), excluding callees outside that address range. */
    fun traceExecution(fromLabel: String, untilLabel: String): ExecutionTrace =
        ExecutionTrace(simulator, textAddress(fromLabel), textAddress(untilLabel)).also(executionTraces::add)

    /** Attach a cold IDE-equivalent data cache at the current stopped instruction boundary. */
    fun connectDataCache(options: CacheOptions = CacheOptions()): DataCacheCheck =
        connectDataCache(options, logAccesses = false)

    /** Opt into a bounded access log as well as the ordinary cache statistics. */
    fun connectDataCache(
        options: CacheOptions = CacheOptions(),
        logAccesses: Boolean,
        logLimit: Int = 65_536,
    ): DataCacheCheck {
        ensureRunnable()
        return DataCacheCheck(machine.memory, options, logAccesses, logLimit).also(dataCaches::add)
    }

    /** Attach the same instruction-clocked MarsBot model used by the IDE, before execution. */
    fun connectMarsBot(instructionsPerTick: Int = MarsBotDevice.DEFAULT_INSTRUCTIONS_PER_TICK): MarsBotCheck {
        requireCase(stop == null && marsBot == null) { "Connect one MarsBot before executing instructions" }
        return MarsBotCheck(machine, instructionsPerTick).also { marsBot = it }
    }

    override fun close() {
        marsBot?.close()
        marsBot = null
        memoryStoreTraces.forEach(MemoryStoreTrace::close)
        memoryStoreTraces.clear()
        memoryAccessTraces.forEach(MemoryAccessTrace::close)
        memoryAccessTraces.clear()
        executionTraces.forEach(ExecutionTrace::close)
        executionTraces.clear()
        dataCaches.forEach(DataCacheCheck::close)
        dataCaches.clear()
    }

    fun startAt(label: String) {
        requireCase(stop == null) { "Choose an entry label before executing instructions" }
        val address = textAddress(label)
        machine.claimStoppedSession().use {
            machine.registerFile.setProgramCounterBits(address)
        }
    }

    /**
     * Call a function in the same stopped session and pause before its supplied continuation.
     * Arguments are prepared with register/memory edits. Entry and RA setup are external edits,
     * establish a new undo baseline, and do not count as executed instructions. Memory, other
     * registers, observations and the cumulative instruction budget remain in this session.
     */
    fun callFunction(label: String, returnTo: String) {
        enterFunction(label, returnTo)
        runUntil(returnTo)
    }

    /**
     * Prepare the same stopped-session entry and continuation as [callFunction], without
     * executing instructions. The checker can then pause at public syscall boundaries or
     * other observations inside the call, including across repeated calls in one case.
     */
    fun enterFunction(label: String, returnTo: String) {
        ensureRunnable()
        val entry = textAddress(label)
        val continuation = textAddress(returnTo)
        requireCase(entry != continuation) { "Choose distinct function and return addresses" }
        machine.claimStoppedSession().use {
            val branch = machine.delayedBranch.snapshot()
            requireCase(!machine.delayedBranch.isRegistered && !machine.delayedBranch.isTriggered &&
                !branch.forbiddenSlot
            ) { "Complete the pending branch delay or forbidden slot before calling a function" }
            machine.editIntegerRegister(31, continuation)
            machine.backStepper.clear()
            machine.registerFile.setProgramCounterBits(entry)
        }
    }

    fun setRegister(register: String, value: Int) {
        setRegister(register, value.toLong())
    }

    fun setRegister(register: String, value: Long) {
        val number = registerNumber(register)
        requireCase(number != 0) { "Cannot write the zero register" }
        machine.editIntegerRegister(number, value)
    }

    /** Pass a data object's actual address to a function, without fixing its allocation address. */
    fun setRegisterToDataAddress(register: String, label: String, offset: Long = 0) {
        setRegister(register, dataAddress(label, offset, alignment = 1))
    }

    /** Select a return continuation or code pointer using its assembled full-width address. */
    fun setRegisterToTextAddress(register: String, label: String) {
        setRegister(register, textAddress(label))
    }

    fun setWord(label: String, value: Int, offset: Int = 0) {
        val address = dataAddress(label, offset)
        try {
            machine.editMemoryWord(address, value)
        } catch (failure: AddressErrorException) {
            throw CaseFailure(failure.message ?: "Cannot write word at $label")
        }
    }

    fun step(instructions: Long = 1) {
        require(instructions > 0) { "Step count must be positive" }
        ensureRunnable()
        checkStop(simulator.runAsync(instructions, null).join(), Reason.STEP_LIMIT)
    }

    /** Pause before executing the instruction at [label]. */
    fun runUntil(label: String) {
        ensureRunnable()
        val address = textAddress(label)
        checkStop(simulator.runUntilAddressAsync(0) { it == address }.join(), Reason.BREAKPOINT)
    }

    /**
     * Pause before a syscall instruction whose current service register selects [number].
     * Uses the simulator's instruction-boundary predicate and non-notifying inspection,
     * so checks need no submission-private labels. Step once to execute a paused service
     * before searching for its next occurrence. This does not enable a restricted service.
     */
    fun runUntilSyscall(number: Int) {
        require(number >= 0) { "Syscall number must be nonnegative" }
        ensureRunnable()
        checkStop(simulator.runUntilAddressAsync(0) { address ->
            machine.registerFile.getLongValueNoNotify(2).toInt() == number &&
                machine.memory.inspectInstruction(address)?.binaryStatement?.let { it and 0xfc00003f.toInt() == 12 } == true
        }.join(), Reason.BREAKPOINT)
    }

    fun runToExit(exitCode: Int = 0) {
        ensureRunnable()
        val result = simulator.runAsync(0, null).join()
        checkStop(result, Reason.NORMAL_EXIT)
        requireCase(result.exitCode == exitCode) { "Expected exit code $exitCode, got ${result.exitCode}" }
    }

    /** Inspect all 64 register bits without advancing execution, notifying observers or editing undo history. */
    fun readRegister(register: String): Long =
        machine.registerFile.getLongValueNoNotify(registerNumber(register))

    fun expectRegister(register: String, expected: Int) {
        expectRegister(register, expected.toLong())
    }

    fun expectRegister(register: String, expected: Long) {
        val actual = readRegister(register)
        requireCase(actual == expected) { "Expected $register = $expected, got $actual" }
    }

    /** Check the raw register halves, with lane zero in [low]. */
    fun expectVector(register: String, low: Long, high: Long) {
        val expected = VectorRegister(low, high)
        val actual = vector(register)
        requireCase(actual == expected) { "Expected $register = ${expected.toHexString()}, got ${actual.toHexString()}" }
    }

    fun expectVectorLane(register: String, lane: Int, width: Int, expected: Long) {
        val actual = vector(register).unsignedLane(lane, width)
        requireCase(actual == expected) { "Expected $register lane $lane ($width bits) = $expected, got $actual" }
    }

    private fun vector(register: String): VectorRegister {
        val name = "$${register.removePrefix("$")}"
        val number = RegisterNames.vector(name) ?: throw CaseFailure("Unknown vector register $register")
        return machine.coprocessor1.getVectorNoNotify(number)
    }

    fun expectWord(label: String, expected: Int, offset: Int = 0) {
        val actual = machine.memory.inspectWord(dataAddress(label, offset))
        requireCase(actual == expected) { "Expected word at $label + $offset = $expected, got $actual" }
    }

    /** Inspect a word without publishing a simulated access or changing undo history. */
    fun readWord(label: String, offset: Int = 0): Int =
        machine.memory.inspectWord(dataAddress(label, offset))
            ?: throw CaseFailure("Cannot inspect word at $label + $offset")

    /** Copy unsigned bytes so a checker can validate relations between student-produced objects. */
    fun readBytes(label: String, length: Int, offset: Int = 0): IntArray {
        require(length >= 0) { "Snapshot length must be nonnegative" }
        dataAddress(label, offset.toLong(), alignment = 1)
        return IntArray(length) { index ->
            val byteOffset = offset.toLong() + index
            machine.memory.inspectDataByte(dataAddress(label, byteOffset, alignment = 1))
                ?: throw CaseFailure("Cannot inspect byte at $label + $byteOffset")
        }
    }

    /**
     * Copy bytes at a complete mapped address, including private stack storage, without
     * publishing simulated accesses or changing undo history. The current memory byte
     * order determines each byte's position in the inspected word. Unmapped bytes fail
     * the case; an empty interval performs no inspection.
     */
    fun readBytes(address: Long, length: Int): IntArray {
        require(address >= 0 && length >= 0 &&
            (length == 0 || address <= Long.MAX_VALUE - (length - 1L))) {
            "Choose a nonnegative byte address and a nonnegative, non-overflowing length"
        }
        var inspectedAddress = -1L
        var word = 0
        return IntArray(length) { index ->
            val byteAddress = address + index
            val wordAddress = byteAddress and -4L
            if (wordAddress != inspectedAddress) {
                word = machine.memory.inspectWord(wordAddress)
                    ?: throw CaseFailure("Cannot inspect byte at 0x${byteAddress.toString(16)}")
                inspectedAddress = wordAddress
            }
            val lane = (byteAddress and 3L).toInt()
            val shift = (if (machine.memory.byteOrder) lane else 3 - lane) * 8
            (word ushr shift) and 0xff
        }
    }

    /** Report a relational contract failure as this case's result and continue with other cases. */
    fun expectCondition(condition: Boolean, message: String) {
        require(message.isNotBlank()) { "Describe the condition being checked" }
        requireCase(condition) { message }
    }

    fun expectBytes(label: String, vararg expected: Int, offset: Int = 0) {
        for ((index, byte) in expected.withIndex()) {
            require(byte in 0..255) { "Expected bytes must be unsigned values from 0 through 255" }
            val actual = machine.memory.inspectDataByte(dataAddress(label, offset.toLong() + index, alignment = 1))
            requireCase(actual == byte) { "Expected byte at $label + ${offset.toLong() + index} = $byte, got $actual" }
        }
    }

    fun expectOutput(expected: String) {
        val actual = output.toString(Charsets.UTF_8)
        requireCase(actual == expected) { "Expected output ${expected.quote()}, got ${actual.quote()}" }
    }

    private fun ensureRunnable() {
        val reason = stop?.reason
        requireCase(reason == null || reason == Reason.STEP_LIMIT || reason == Reason.BREAKPOINT) {
            "Execution has already stopped"
        }
    }

    private fun checkStop(result: StopEvent, expected: Reason) {
        stop = result
        requireCase(result.reason == expected) {
            val detail = error?.let { " ($it)" }.orEmpty()
            "Expected execution to reach $expected, stopped at ${result.reason}$detail"
        }
    }

    private fun registerNumber(register: String): Int {
        requireCase(register.isNotBlank() && register != "\$") { "Unknown register: $register" }
        val name = if (register.startsWith('$')) register else "$$register"
        return machine.registerFile.getUserRegister(name)?.number
            ?: throw CaseFailure("Unknown register: $register")
    }

    private fun definition(label: String): AssemblySymbolMap.Definition {
        symbols.globals()[label]?.let { return it }
        val definitions = symbols.locals().values.mapNotNull { it[label]?.takeUnless(AssemblySymbolMap.Definition::macroPrivate) }
        requireCase(definitions.size <= 1) { "Label is ambiguous: $label" }
        return definitions.singleOrNull() ?: throw CaseFailure("Missing label: $label")
    }

    private fun textAddress(label: String): Long {
        val definition = definition(label)
        val address = definition.addressBits
        requireCase(definition.kind == AssemblySymbolMap.Kind.TEXT && address % Int.SIZE_BYTES == 0L &&
            machine.memory.inspectInstruction(address) != null) {
            "Expected an instruction at text label: $label"
        }
        return address
    }

    private fun dataAddress(label: String, offset: Int): Long = dataAddress(label, offset.toLong(), Int.SIZE_BYTES)

    private fun dataAddress(label: String, offset: Long, alignment: Int): Long {
        val definition = definition(label)
        requireCase(definition.kind == AssemblySymbolMap.Kind.DATA || definition.kind == AssemblySymbolMap.Kind.EXTERNAL) {
            "Expected a data label: $label"
        }
        val base = if (definition.addressBits == definition.address.toLong()) definition.address.toUInt().toLong()
        else definition.addressBits
        val address = base + offset
        requireCase(address % alignment == 0L && machine.memory.inUserData(address)) {
            "Invalid data address at $label + $offset"
        }
        return address
    }
}

internal class CaseFailure(message: String) : RuntimeException(message)

private class AssemblyLimitReached : CancellationException()

internal inline fun requireCase(condition: Boolean, message: () -> String) {
    if (!condition) throw CaseFailure(message())
}

private fun String.quote() = "\"${take(200).replace("\n", "\\n").replace("\r", "\\r")}\""

internal class BoundedOutput(private val bytes: ByteArrayOutputStream) : OutputStream() {
    override fun write(value: Int) {
        checkCapacity(1)
        bytes.write(value)
    }

    override fun write(values: ByteArray, offset: Int, length: Int) {
        checkCapacity(length)
        bytes.write(values, offset, length)
    }

    private fun checkCapacity(length: Int) {
        requireCase(length <= 64 * 1024 - bytes.size()) { "Program output exceeds 64 KiB" }
    }
}

internal class GradingEnvironment(
    console: SyscallEnvironment,
    private val files: GradingFiles,
    private val timing: GradingTiming,
) : SyscallEnvironment by console {
    override fun allowsSyscall(number: Int): Boolean = when (number) {
        in 1..12, 17, in 34..36 -> true
        13, 14, 16 -> files.enabled
        15 -> files.writesEnabled
        30, 32 -> timing.enabled
        else -> false
    }

    override fun openFile(path: String?, flags: Int): Int = files.open(path, flags)
    override fun readFromFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
        files.read(fd, buffer, lengthRequested)
    override fun writeToFile(fd: Int, buffer: ByteArray?, lengthRequested: Int): Int =
        files.write(fd, buffer, lengthRequested)
    override fun closeFile(fd: Int) = files.close(fd)
    override fun currentTimeMillis(): Long = timing.currentTimeMillis()
    override fun sleepMillis(milliseconds: Int) = timing.sleepMillis(milliseconds)
}
