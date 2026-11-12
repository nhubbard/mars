package edu.missouristate.mars.core

import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SimulatorState
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.CONDITION_FLAG_MASK
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.mips.hardware.MemoryConfigurations
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.mips.instructions.SyscallRegistry
import edu.missouristate.mars.mips.instructions.syscalls.RandomStreams
import edu.missouristate.mars.simulator.BackStepper
import edu.missouristate.mars.simulator.DelayedBranchState
import edu.missouristate.mars.simulator.SimulatorEngine
import edu.missouristate.mars.util.Binary
import java.nio.file.Path
import java.util.Objects
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.IntSupplier
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** Owns the settings, hardware, assembly, and execution state of one MIPS session. */
@Singleton
class MarsContext @Inject constructor(
    @JvmField val settings: SimulationSettings,
    @JvmField val gui: MarsGuiProxy,
    @JvmField val registerFile: IntegerRegisterFile,
    @JvmField val coprocessor0: Coprocessor0State,
    @JvmField val coprocessor1: Coprocessor1State,
    @JvmField val delayedBranch: DelayedBranchState,
    @JvmField val memory: MemoryState,
    @JvmField val instructionSet: InstructionSet,
    @JvmField val backStepper: BackStepper,
    @JvmField val symbolTable: SymbolTable,
) : AutoCloseable {
    @JvmField val syscallEnvironment = SessionSyscallEnvironment(memory)
    @JvmField val randomStreams = RandomStreams()
    @JvmField val syscalls = SyscallRegistry()

    private val interrupt = AtomicInteger()
    private val deviceInterrupts = AtomicInteger()
    private val executionLock = ReentrantLock()
    @Volatile private var controller: SimulatorEngine? = null
    @Volatile var program: MIPSprogram? = null
        private set
    @Volatile var isClosing: Boolean = false
        private set

    init {
        settings.setBackstepStatus { program != null && backStepper.enabled() }
    }

    /** Requests an interrupt to be handled at the next instruction boundary. */
    fun requestExternalInterrupt(cause: Int) {
        interrupt.set(cause)
    }

    internal fun takeExternalInterrupt(): Int = interrupt.getAndSet(0) or deviceInterrupts.getAndSet(0)

    fun pendingDeviceInterrupts(): Int = deviceInterrupts.get()

    /** Updates the selected bits while preserving other devices' interrupt causes. */
    fun setDeviceInterrupts(mask: Int, causes: Int) {
        deviceInterrupts.getAndUpdate { pending -> (pending and mask.inv()) or (causes and mask) }
    }

    /** Creates the asynchronous controller on demand. */
    @Synchronized
    fun simulator(): SimulatorEngine {
        check(!isClosing) { "Session is closed" }
        return controller ?: SimulatorEngine(this).also { controller = it }
    }

    /** Claims an instruction boundary or a reentrant lease from the owning execution thread. */
    fun claimExecution(): ExecutionLease {
        check(controller?.executingOnAnotherThread() != true) { "Simulator is running" }
        check(executionLock.tryLock()) { "Session is already in use" }
        return ExecutionLease(executionLock)
    }

    fun claimStoppedSession(): ExecutionLease {
        check(!isClosing) { "Session is closed" }
        val lease = claimExecution()
        if (controller?.state() == SimulatorState.RUNNING) {
            lease.close()
            error("Simulator is running")
        }
        return lease
    }

    class ExecutionLease internal constructor(private val lock: ReentrantLock) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                lock.unlock()
                closed = true
            }
        }
    }

    /** Changes a writable CPU register and establishes a new undo baseline. */
    fun editIntegerRegister(number: Int, bits: Int): Boolean {
        require(number >= 1 &&
            (number <= 31 || number == IntegerRegisterFile.HI_REGISTER || number == IntegerRegisterFile.LO_REGISTER)
        ) { "Register is not editable: $number" }
        claimStoppedSession().use {
            if (registerFile.getValueNoNotify(number) == bits) return false
            backStepper.clear()
            registerFile.updateRegister(number, bits)
            return true
        }
    }

    fun editCoprocessor0Register(number: Int, bits: Int): Boolean {
        val register = requireNotNull(coprocessor0.getRegister(number)) { "Coprocessor 0 register is not implemented: $number" }
        return editRegister(register, bits)
    }

    fun editCoprocessor1Register(number: Int, bits: Int): Boolean {
        val registers = coprocessor1.registers
        return editRegister(registers[Objects.checkIndex(number, registers.size)], bits)
    }

    @Throws(InvalidRegisterAccessException::class)
    fun editCoprocessor1Pair(number: Int, bits: Long): Boolean {
        val registers = coprocessor1.registers
        require(number >= 0 && number < registers.lastIndex && number % 2 == 0) {
            "Coprocessor 1 register pair is not implemented: $number"
        }
        claimStoppedSession().use {
            val current = Binary.twoIntsToLong(registers[number + 1].valueNoNotify, registers[number].valueNoNotify)
            if (current == bits) return false
            backStepper.clear()
            coprocessor1.setRegisterPairToLong(number, bits)
            return true
        }
    }

    fun editCoprocessor1ConditionFlags(bits: Int): Boolean {
        require((bits and CONDITION_FLAG_MASK.inv()) == 0) { "Coprocessor 1 condition flags must fit in eight bits" }
        claimStoppedSession().use {
            if ((coprocessor1.conditionFlagsNoNotify and CONDITION_FLAG_MASK) == bits) return false
            backStepper.clear()
            coprocessor1.setConditionFlagBits(bits)
            return true
        }
    }

    private fun editRegister(register: Register, bits: Int): Boolean {
        claimStoppedSession().use {
            if (register.valueNoNotify == bits) return false
            backStepper.clear()
            register.setValue(bits)
            return true
        }
    }

    /** Writes a word using session memory policy and establishes a new undo baseline. */
    @Throws(AddressErrorException::class)
    fun editMemoryWord(address: Int, bits: Int): Boolean {
        claimStoppedSession().use {
            val protectedText = memory.inKernelTextSegment(address) ||
                memory.inTextSegment(address) && !settings.isSelfModifyingCodeEnabled
            if (!protectedText && memory.inspectWord(address) == bits) return false
            memory.setWord(address, bits)
            backStepper.clear()
            return true
        }
    }

    /** Waits for an instruction boundary before closing default resources. */
    fun releaseDefaultResources() {
        executionLock.withLock { syscallEnvironment.close() }
    }

    fun setMemoryConfiguration(identifier: String?) {
        claimStoppedSession().use { setMemoryConfigurationStopped(identifier) }
    }

    private fun setMemoryConfigurationStopped(identifier: String?) {
        val configuration = MemoryConfigurations.createConfiguration(identifier)
        syscallEnvironment.close()
        backStepper.clear()
        program = null
        symbolTable.clear()
        memory.setConfiguration(configuration)
        memory.clear()
        settings.setMemoryConfiguration(configuration.configurationIdentifier())
        registerFile.getUserRegister("\$gp")!!.changeResetValue(configuration.globalPointer)
        registerFile.getUserRegister("\$sp")!!.changeResetValue(configuration.stackPointer)
        registerFile.programCounterRegister.changeResetValue(configuration.textBaseAddress)
        registerFile.reset()
        clearPendingEvents()
    }

    private fun clearPendingEvents() {
        delayedBranch.clear()
        interrupt.set(0)
        deviceInterrupts.set(0)
    }

    enum class AssemblyPhase { TOKENIZING, ASSEMBLING }

    @Throws(ProcessingException::class)
    fun assemble(sources: List<Path>?): MIPSprogram =
        assemble(sources, null, settings.isExtendedAssemblerEnabled, settings.isWarningsAreErrors)

    @Throws(ProcessingException::class)
    fun assemble(sources: List<Path>?, handler: Path?, extended: Boolean, warnings: Boolean): MIPSprogram =
        claimStoppedSession().use { assembleStopped(sources, handler, extended, warnings, null, {}, null) }

    @Throws(ProcessingException::class)
    fun assemble(sources: List<Path>?, phaseStarted: Consumer<AssemblyPhase>): MIPSprogram =
        claimStoppedSession().use {
            assembleStopped(sources, null, settings.isExtendedAssemblerEnabled, settings.isWarningsAreErrors, null, {}, phaseStarted)
        }

    @JvmOverloads
    @Throws(ProcessingException::class)
    fun assembleSnapshot(
        snapshot: AssemblySourceSnapshot, handler: Path?, extended: Boolean, warnings: Boolean, checkCancellation: Runnable = Runnable {},
    ): MIPSprogram = claimStoppedSession().use {
        assembleStopped(snapshot.entries(), handler, extended, warnings, snapshot, checkCancellation, null)
    }

    private fun assembleStopped(
        sources: List<Path>?, handler: Path?, extended: Boolean, warnings: Boolean, snapshot: AssemblySourceSnapshot?,
        checkCancellation: Runnable, phaseStarted: Consumer<AssemblyPhase>?,
    ): MIPSprogram {
        require(!sources.isNullOrEmpty()) { "At least one source file is required" }
        val root = MIPSprogram(this, snapshot)
        val filenames = sources.mapTo(ArrayList(sources.size)) {
            checkCancellation.run()
            it.toAbsolutePath().normalize().toString()
        }
        phaseStarted?.accept(AssemblyPhase.TOKENIZING)
        val files = root.prepareFilesForAssembly(filenames, filenames.first(), handler?.toAbsolutePath()?.toString(), checkCancellation)
        phaseStarted?.accept(AssemblyPhase.ASSEMBLING)
        root.assemble(files, extended, warnings, checkCancellation)
        return root
    }

    /** Captures a newly assembled program before execution or frontend edits. */
    fun captureAssemblyImage(checkCancellation: Runnable): AssemblyImage = claimStoppedSession().use {
        val current = checkNotNull(program) { "No assembled program" }
        val instructions = current.machineList!!.mapTo(ArrayList(current.instructionCount())) {
            checkCancellation.run()
            it.captureImage()
        }
        checkCancellation.run()
        AssemblyImage(
            current.filename!!, memory.getConfiguration().configurationIdentifier()!!, registerFile.programCounter,
            instructions, memory.captureData(checkCancellation), current.sourceMap(), current.symbolMap(),
        )
    }

    fun loadAssemblyImage(image: AssemblyImage, checkCancellation: Runnable) {
        loadAssemblyImage(image, checkCancellation) { image.entry }
    }

    fun loadAssemblyImage(image: AssemblyImage, startAtMain: Boolean, checkCancellation: Runnable) {
        loadAssemblyImage(image, checkCancellation) { programEntryAddress(startAtMain) }
    }

    private fun loadAssemblyImage(image: AssemblyImage, checkCancellation: Runnable, entryAddress: IntSupplier) {
        claimStoppedSession().use {
            val instructions = image.instructions.mapTo(ArrayList(image.instructions.size)) {
                checkCancellation.run()
                it.load(instructionSet, settings)
            }
            checkCancellation.run()
            val loaded = MIPSprogram.fromImage(this, image.filename!!, instructions, image.sources, image.symbols)
            setMemoryConfigurationStopped(image.layout)
            beginAssembly(loaded)
            memory.loadData(image.data, checkCancellation)
            for (statement in instructions) {
                checkCancellation.run()
                try {
                    memory.setStatement(statement.address, statement)
                } catch (invalidAddress: AddressErrorException) {
                    throw IllegalStateException("Invalid instruction address in assembly image", invalidAddress)
                }
            }
            symbolTable.loadDefinitions(image.symbols.globals())
            registerFile.initializeProgramCounter(entryAddress.asInt)
            program = loaded
        }
    }

    internal fun beginAssembly(candidate: MIPSprogram) {
        require(candidate.assemblyContext().session === this) { "Program belongs to another session" }
        syscallEnvironment.close()
        backStepper.clear()
        program = null
        registerFile.reset()
        coprocessor0.resetRegisters()
        coprocessor1.resetRegisters()
        clearPendingEvents()
    }

    internal fun finishAssembly(candidate: MIPSprogram) {
        program = candidate
        registerFile.initializeProgramCounter(programEntryAddress(settings.isStartAtMainEnabled))
    }

    /** Closes default I/O. Caller-supplied syscall environments remain caller-owned. */
    override fun close() {
        val active = synchronized(this) {
            if (isClosing) return
            isClosing = true
            controller
        }
        if (active != null) active.close() else releaseDefaultResources()
    }

    private fun programEntryAddress(startAtMain: Boolean): Int {
        val entry = if (startAtMain) symbolTable.getAddress(SymbolTable.getStartLabel()) else SymbolTable.NOT_FOUND
        return if (entry != SymbolTable.NOT_FOUND && (memory.inTextSegment(entry) || memory.inKernelTextSegment(entry))) {
            entry
        } else memory.textBaseAddress
    }
}
