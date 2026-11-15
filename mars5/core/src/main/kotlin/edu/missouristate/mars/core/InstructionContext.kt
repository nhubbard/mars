package edu.missouristate.mars.core

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.MarsSettings
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.StepEvent.BranchOutcome
import edu.missouristate.mars.api.StepEvent.Call
import edu.missouristate.mars.api.StepEvent.ExceptionEntry
import edu.missouristate.mars.api.StepEvent.ExceptionReturn
import edu.missouristate.mars.api.StepEvent.Jump
import edu.missouristate.mars.api.StepEvent.Transfer
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.EPC
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.EXCEPTION_LEVEL
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.STATUS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.CAUSE_MASK
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.hasEnabledFloatingException
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.J_FORMAT
import edu.missouristate.mars.mips.instructions.Instruction.Companion.INSTRUCTION_LENGTH
import edu.missouristate.mars.mips.instructions.SyscallRegistry
import edu.missouristate.mars.mips.instructions.syscalls.RandomStreams
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE
import edu.missouristate.mars.simulator.Exceptions.FLOATING_POINT_EXCEPTION
import edu.missouristate.mars.simulator.Exceptions.MSA_FLOATING_POINT_EXCEPTION
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION
import edu.missouristate.mars.util.Binary

private val indirectControlTransfers = setOf("jr", "jalr", "jr.hb", "jalr.hb", "jic", "jialc", "eret", "eretnc", "deret", "nal", "wait", "pause")

/** Executes instructions against one session's registers, memory, undo history, and syscall I/O. */
class InstructionContext(private val session: MarsContext, private val environment: SyscallEnvironment) {
    @JvmField val registerFile = session.registerFile
    @JvmField val coprocessor0 = session.coprocessor0
    @JvmField val coprocessor1 = session.coprocessor1
    @JvmField val delayedBranch = session.delayedBranch

    var exitCode: Int = 0
        private set
    private var lastTransfer: Transfer? = null
    private var lastBranchOutcome: BranchOutcome? = null

    fun syscalls(): SyscallRegistry = session.syscalls

    fun randomStreams(): RandomStreams = session.randomStreams

    fun settings(): MarsSettings = session.settings

    fun environment(): SyscallEnvironment = environment

    /** Read an operand of an instruction that uses the target's full integer register width. */
    internal fun readIntegerOperand(number: Int): Long =
        if (session.target == MarsTarget.MARS64) registerFile.getLongValue(number)
        else registerFile.getValue(number).toLong()

    /** Compute the address before looking up the segment that services the access. */
    internal fun effectiveAddress(register: Int, offset: Int): Long {
        if (session.target == MarsTarget.MARS32) return (registerFile.getValue(register) + offset).toLong()
        val address = registerFile.getLongValue(register) + offset.toShort()
        val narrow = address.toInt()
        return if (address == narrow.toUInt().toLong()) narrow.toLong() else address
    }

    internal fun legacyEffectiveAddress(register: Int, offset: Int, exception: Int = ADDRESS_EXCEPTION_LOAD): Int =
        memory().narrowAddress(effectiveAddress(register, offset), exception)

    internal fun jumpAndLinkRegister(link: Int, source: Int) {
        linkReturnAddress(link)
        jump(registerFile.getValue(source))
    }

    fun updateRegister(number: Int, value: Int): Int {
        if (number == 32) return setProgramCounter(value)
        return updateLongRegister(number, value.toLong()).toInt()
    }

    internal fun updateLongRegister(number: Int, value: Long): Long {
        val previous = registerFile.updateLongRegister(number, value)
        if (number != 0 && session.backStepper.enabled()) {
            session.backStepper.addRegisterFileRestore(number, previous)
        }
        return previous
    }

    fun setProgramCounter(value: Int): Int = setProgramCounter(value.toLong()).toInt()

    private fun setProgramCounter(value: Long): Long {
        val previous = registerFile.setProgramCounterBits(value)
        if (session.backStepper.enabled()) session.backStepper.addPCRestore(previous)
        return previous
    }

    /** Save the address after the jump or its delay slot, as configured for this session. */
    internal fun linkReturnAddress(register: Int) {
        val delay = if (usesDelaySlots()) INSTRUCTION_LENGTH else 0
        link(register, registerFile.programCounterBits + delay)
    }

    /** Write the link register and record a call if the register accepts writes. */
    fun link(register: Int, returnAddress: Int) = link(register, returnAddress.toLong())

    internal fun link(register: Int, returnAddress: Long) {
        val bits = if (session.target == MarsTarget.MARS64) returnAddress else returnAddress.toInt().toLong()
        updateLongRegister(register, bits)
        if (register != 0) lastTransfer = Call(register, bits.toInt(), bits)
    }

    fun lastTransfer(): Transfer? = lastTransfer

    fun lastBranchOutcome(): BranchOutcome? = lastBranchOutcome

    @JvmOverloads
    fun branch(displacement: Int, taken: Boolean = true) {
        if (session.target == MarsTarget.MARS64) {
            release6DelayedBranch(displacement, taken)
            return
        }
        if (!taken) return
        val target = registerFile.programCounter + (displacement shl 2)
        lastBranchOutcome = BranchOutcome.TAKEN
        if (settings().isDelayedBranchingEnabled) {
            delayedBranch.register(target)
        } else {
            setProgramCounter(target)
        }
    }

    /** Register the architectural delay slot, including when the branch falls through. */
    internal fun release6DelayedBranch(displacement: Int, taken: Boolean) {
        val next = registerFile.programCounterBits
        val target = next + if (taken) (displacement shl 2) else INSTRUCTION_LENGTH
        delayedBranch.register(target)
        lastBranchOutcome = if (taken) BranchOutcome.TAKEN else BranchOutcome.NOT_TAKEN
    }

    internal fun release6DelayedJump(target: Long, linkRegister: Int) {
        if (linkRegister != 0) link(linkRegister, registerFile.programCounterBits + INSTRUCTION_LENGTH)
        delayedBranch.register(target, jump = linkRegister == 0)
    }

    /** Execute or schedule a jump. Linking instructions have already recorded a call. */
    fun jump(target: Int) = jump(target.toLong())

    internal fun jump(target: Long) {
        val withoutLink = lastTransfer == null
        if (usesDelaySlots()) {
            delayedBranch.register(target, withoutLink)
        } else {
            setProgramCounter(target)
            if (withoutLink) lastTransfer = Jump(target.toInt(), target)
        }
    }

    private fun usesDelaySlots(): Boolean = session.target == MarsTarget.MARS64 || settings().isDelayedBranchingEnabled

    /** Transfer control immediately, even when legacy branch delay slots are enabled. */
    internal fun compactTransfer(statement: ProgramStatement, target: Long, branch: Boolean = false) {
        checkCompactTransferSlot(statement)
        setProgramCounter(target)
        if (branch) lastBranchOutcome = BranchOutcome.TAKEN
        else if (lastTransfer == null) lastTransfer = Jump(target.toInt(), target)
    }

    internal fun checkCompactTransferSlot(statement: ProgramStatement) {
        if (delayedBranch.isTriggered) {
            throw ProcessingException(this, statement, "compact control transfer in a delay slot", RESERVED_INSTRUCTION_EXCEPTION)
        }
    }

    fun returnFromException() {
        finishExceptionReturn(coprocessor0.getValue(EPC).toLong(), clearReservation = true)
    }

    internal fun release6ExceptionReturn(statement: ProgramStatement, clearReservation: Boolean) {
        val target = try {
            memory().checkInstructionAddress(coprocessor0.readRegisterBits(EPC))
        } catch (failure: AddressErrorException) {
            throw ProcessingException(this, statement, failure)
        }
        updateCoprocessor0(STATUS, Binary.clearBit(coprocessor0.getValue(STATUS), EXCEPTION_LEVEL))
        finishExceptionReturn(target, clearReservation)
    }

    private fun finishExceptionReturn(target: Long, clearReservation: Boolean) {
        if (clearReservation) memory().reservation = null
        setProgramCounter(target)
        lastTransfer = ExceptionReturn.INSTANCE
    }

    fun updateCoprocessor0(number: Int, value: Int): Int {
        if (coprocessor0.getRegister(number) == null) return 0
        return updateCoprocessor0Bits(number, value.toLong()).toInt()
    }

    internal fun updateCoprocessor0Bits(number: Int, value: Long): Long {
        val previous = coprocessor0.updateRegisterBits(number, value)
        if (session.backStepper.enabled()) session.backStepper.addCoprocessor0Restore(number, previous)
        return previous
    }

    fun updateCoprocessor1(number: Int, value: Int): Int {
        val previous = coprocessor1.updateLowWord(number, value)
        if (number in 0..31 && session.backStepper.enabled()) {
            if (coprocessor1.hasWideRegisters) session.backStepper.addCoprocessor1Restore(number, previous)
            else session.backStepper.addCoprocessor1Restore(number, previous.toInt())
        }
        return previous.toInt()
    }

    internal fun updateCoprocessor1Doubleword(number: Int, value: Long) {
        val previous = coprocessor1.updateLongRegister(number, value)
        if (session.backStepper.enabled()) session.backStepper.addCoprocessor1Restore(number, previous)
    }

    internal fun updateVectorRegister(number: Int, value: VectorRegister) {
        val previous = coprocessor1.updateVector(number, value)
        if (session.backStepper.enabled()) {
            session.backStepper.addCoprocessor1Restore(number, previous.low)
            session.backStepper.addVectorHighRestore(number, previous.high)
        }
    }

    internal fun writeMsaStatus(statement: ProgramStatement, bits: Int) {
        val previous = coprocessor1.updateMsaStatus(bits)
        if (session.backStepper.enabled()) session.backStepper.addMsaStatusRestore(previous)
        if (hasEnabledFloatingException(coprocessor1.msaStatus)) {
            throw ProcessingException(this, statement, "MSA floating-point exception", MSA_FLOATING_POINT_EXCEPTION)
        }
    }

    internal fun writeFloatingControl(statement: ProgramStatement, register: Int, bits: Int) {
        val status = coprocessor1.controlWriteValue(register, bits)
            ?: throw ProcessingException(this, statement, "Reserved COP1 control register $register", RESERVED_INSTRUCTION_EXCEPTION)
        updateFloatingStatus(status)
        checkFloatingException(statement, status)
    }

    /** Record the current operation's causes and accumulate flags for operations that complete. */
    internal fun recordFloatingExceptions(statement: ProgramStatement, causes: Int) {
        val previous = coprocessor1.floatingStatus
        val status = (previous and CAUSE_MASK.inv()) or (causes shl 12)
        if (hasEnabledFloatingException(status)) {
            updateFloatingStatus(status)
            checkFloatingException(statement, status)
        } else {
            updateFloatingStatus(status or (causes shl 2))
        }
    }

    private fun updateFloatingStatus(status: Int) {
        if (status == coprocessor1.floatingStatus) return
        val previous = coprocessor1.updateFloatingStatus(status)
        if (session.backStepper.enabled()) session.backStepper.addFloatingStatusRestore(previous)
    }

    private fun checkFloatingException(statement: ProgramStatement, status: Int) {
        if (hasEnabledFloatingException(status)) {
            throw ProcessingException(this, statement, "Floating-point exception", FLOATING_POINT_EXCEPTION)
        }
    }

    fun setConditionFlag(flag: Int): Int = recordConditionFlag(flag, coprocessor1.setConditionFlag(flag))

    fun clearConditionFlag(flag: Int): Int = recordConditionFlag(flag, coprocessor1.clearConditionFlag(flag))

    private fun recordConditionFlag(flag: Int, previous: Int): Int {
        if (flag in 0 until coprocessor1.conditionFlagCount && session.backStepper.enabled()) {
            if (previous == 0) {
                session.backStepper.addConditionFlagClear(flag)
            } else {
                session.backStepper.addConditionFlagSet(flag)
            }
        }
        return previous
    }

    fun memory(): MemoryState = session.memory

    internal fun loadWord(address: Long): Int =
        if (session.target == MarsTarget.MARS64 && address and 3 != 0L) loadUnaligned(address, 4).toInt()
        else memory().readData(address, 4)

    internal fun loadHalf(address: Long): Int =
        if (session.target == MarsTarget.MARS64 && address and 1 != 0L) loadUnaligned(address, 2).toInt()
        else memory().readData(address, 2)

    internal fun storeWord(address: Long, value: Int) {
        if (session.target == MarsTarget.MARS64 && address and 3 != 0L) storeUnaligned(address, 4, value.toLong())
        else storeData(address, 4, value)
    }

    internal fun storeHalf(address: Long, value: Int) {
        if (session.target == MarsTarget.MARS64 && address and 1 != 0L) storeUnaligned(address, 2, value.toLong())
        else storeData(address, 2, value)
    }

    internal fun loadDoubleword(address: Long): Long {
        if (address and 7 != 0L) return loadUnaligned(address, 8)
        val memory = memory()
        val first = memory.readData(address, 4).toUInt().toLong()
        val second = memory.readData(address + 4, 4).toUInt().toLong()
        return if (memory.byteOrder) (second shl 32) or first else (first shl 32) or second
    }

    internal fun storeDoubleword(address: Long, value: Long) {
        if (address and 7 != 0L) {
            storeUnaligned(address, 8, value)
            return
        }
        val low = value.toInt()
        val high = (value ushr 32).toInt()
        val little = memory().byteOrder
        storeData(address, 4, if (little) low else high)
        storeData(address + 4, 4, if (little) high else low)
    }

    internal fun loadUnaligned(address: Long, size: Int): Long {
        val memory = session.memory
        memory.checkOrdinaryAccess(address, size, ADDRESS_EXCEPTION_LOAD)
        val little = memory.byteOrder
        var value = 0L
        for (index in 0 until size) {
            val shift = (if (little) index else size - 1 - index) * Byte.SIZE_BITS
            value = value or ((memory.readData(address + index, 1).toLong() and 0xff) shl shift)
        }
        return value
    }

    internal fun storeUnaligned(address: Long, size: Int, value: Long) {
        val memory = session.memory
        memory.checkOrdinaryAccess(address, size, ADDRESS_EXCEPTION_STORE)
        val little = memory.byteOrder
        for (index in 0 until size) {
            val shift = (if (little) index else size - 1 - index) * Byte.SIZE_BITS
            storeData(address + index, 1, (value ushr shift).toInt() and 0xff)
        }
    }

    internal fun storeData(address: Long, size: Int, value: Int) {
        val previous = memory().writeData(address, size, value)
        if (session.backStepper.enabled()) session.backStepper.addMemoryRestore(address, size, previous)
    }

    internal fun loadPcRelativeWord(address: Long): Int {
        val memory = memory()
        if (memory.inWideText(address)) return memory.fetchInstruction(address, notify = false)?.binaryStatement ?: 0
        val location = address.toInt()
        return if (address == location.toLong() &&
            (memory.inTextSegment(location) || memory.inKernelTextSegment(location))
        ) {
            memory.getStatement(location)?.binaryStatement ?: 0
        } else {
            memory.readData(address, 4)
        }
    }

    @Throws(AddressErrorException::class)
    fun setMemoryWord(address: Int, value: Int): Int {
        val previous = session.memory.setWord(address, value)
        if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreWord(address, previous)
        return previous
    }

    @Throws(AddressErrorException::class)
    fun setMemoryHalf(address: Int, value: Int): Int {
        val previous = session.memory.setHalf(address, value)
        if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreHalf(address, previous)
        return previous
    }

    @Throws(AddressErrorException::class)
    fun setMemoryByte(address: Int, value: Int): Int {
        val previous = session.memory.setByte(address, value)
        if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreByte(address, previous)
        return previous
    }

    @Throws(AddressErrorException::class)
    fun setMemoryRawWord(address: Int, value: Int): Int {
        val previous = session.memory.setRawWord(address, value)
        if (session.backStepper.enabled()) session.backStepper.addMemoryRestoreRawWord(address, previous)
        return previous
    }

    fun allocateBytesFromHeap(bytes: Int): Int {
        val previous = session.memory.heapAddress
        val address = session.memory.allocateBytesFromHeap(bytes)
        if (previous != session.memory.heapAddress && session.backStepper.enabled()) {
            session.backStepper.addHeapRestore(previous)
        }
        return address
    }

    fun setRegisterToFloat(number: Int, value: Float) {
        if (number in 0..31) updateCoprocessor1(number, value.toRawBits())
    }

    fun setRegisterToFloat(name: String?, value: Float) {
        setRegisterToFloat(coprocessor1.getRegisterNumber(name), value)
    }

    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(number: Int, value: Double) {
        if (coprocessor1.hasWideRegisters) {
            updateCoprocessor1Doubleword(number, value.toRawBits())
            return
        }
        if (number % 2 != 0) throw InvalidRegisterAccessException()
        if (number < 0 || number >= coprocessor1.registers.size - 1) throw ArrayIndexOutOfBoundsException(number)
        val bits = value.toRawBits()
        updateCoprocessor1(number + 1, Binary.highOrderLongToInt(bits))
        updateCoprocessor1(number, Binary.lowOrderLongToInt(bits))
    }

    @Throws(InvalidRegisterAccessException::class)
    fun setRegisterPairToDouble(name: String?, value: Double) {
        setRegisterPairToDouble(coprocessor1.getRegisterNumber(name), value)
    }

    /** Execute one cycle, including a delay slot and the instruction's undo group. */
    @Throws(ProcessingException::class)
    fun execute(statement: ProgramStatement) {
        execute(statement, dispatchHandler = false)
    }

    /** Execute one cycle and dispatch recoverable faults to the session's exception handler. */
    @Throws(ProcessingException::class)
    fun executeWithExceptionHandler(statement: ProgramStatement) {
        execute(statement, dispatchHandler = true)
    }

    internal fun executeFetchFault(statement: ProgramStatement, error: AddressErrorException) {
        executeInstruction(statement, dispatchHandler = true, fetchError = error)
    }

    fun newErrorList(): ErrorList = ErrorList(session.settings)

    private fun execute(statement: ProgramStatement, dispatchHandler: Boolean) {
        AccessOrigin.duringExecution<Unit, ProcessingException> {
            executeInstruction(statement, dispatchHandler)
        }
    }

    private fun executeInstruction(
        statement: ProgramStatement, dispatchHandler: Boolean, fetchError: AddressErrorException? = null,
    ) {
        lastTransfer = null
        lastBranchOutcome = null
        val history = session.backStepper
        val recording = history.beginInstruction(registerFile.programCounterBits, statement)
        try {
            registerFile.incrementPC()
            try {
                if (fetchError != null) throw ProcessingException(this, statement, fetchError)
                val interrupt = session.takeExternalInterrupt()
                if (interrupt != 0) throw ProcessingException(this, statement, "External interrupt", interrupt)
                val instruction = statement.instruction as? BasicInstruction
                    ?: throw ProcessingException(this, statement, "invalid instruction", RESERVED_INSTRUCTION_EXCEPTION)
                if (instruction.isControlTransfer()) {
                    if (delayedBranch.forbiddenSlot) {
                        throw ProcessingException(this, statement, "control transfer in a forbidden slot", RESERVED_INSTRUCTION_EXCEPTION)
                    }
                    if (session.target == MarsTarget.MARS64 && delayedBranch.isTriggered) {
                        throw ProcessingException(this, statement, "control transfer in a delay slot", RESERVED_INSTRUCTION_EXCEPTION)
                    }
                }
                // Fault construction needs to see the incoming slot until execution finishes.
                instruction.simulationCode.simulate(statement, this)
                delayedBranch.forbiddenSlot = false
                if (instruction.instructionFormat == I_BRANCH_FORMAT && lastBranchOutcome == null) {
                    lastBranchOutcome = BranchOutcome.NOT_TAKEN
                    if (session.target == MarsTarget.MARS64 && instruction.isCompactConditionalBranch) {
                        delayedBranch.forbiddenSlot = true
                    }
                }
            } catch (error: ProcessingException) {
                delayedBranch.forbiddenSlot = false
                memory().reservation = null
                if (!dispatchHandler || error.errors() == null) throw error
                val handler = try {
                    memory().getStatementNoNotify(memory().exceptionHandlerAddress)
                } catch (_: AddressErrorException) {
                    null
                }
                if (handler == null) throw error
                setProgramCounter(memory().exceptionHandlerAddress)
                delayedBranch.clear()
                lastTransfer = ExceptionEntry.INSTANCE
                lastBranchOutcome = null
            }
            completeInstruction()
        } finally {
            if (recording) history.endInstruction()
        }
    }

    private fun BasicInstruction.isControlTransfer(): Boolean =
        instructionFormat == I_BRANCH_FORMAT || instructionFormat == J_FORMAT || name in indirectControlTransfers

    fun completeInstruction() {
        if (delayedBranch.isTriggered) {
            val target = delayedBranch.branchTargetAddressBits
            setProgramCounter(target)
            if (delayedBranch.isJump && lastTransfer == null) lastTransfer = Jump(target.toInt(), target)
            delayedBranch.clear()
        } else if (delayedBranch.isRegistered) {
            delayedBranch.trigger()
        }
    }

    fun setExceptionRegisters(cause: Int) {
        val status = coprocessor0.getValue(STATUS)
        var causeBits = (coprocessor0.getValue(CAUSE) and 0xfffffc83.toInt()) or (cause shl 2)
        val faultPc = registerFile.programCounterBits - INSTRUCTION_LENGTH
        if (session.target == MarsTarget.MARS32) {
            updateCoprocessor0(EPC, faultPc.toInt())
        } else if (status and (1 shl EXCEPTION_LEVEL) == 0) {
            val inBranchSlot = delayedBranch.isTriggered || delayedBranch.forbiddenSlot
            causeBits = (causeBits and Int.MAX_VALUE) or (if (inBranchSlot) Int.MIN_VALUE else 0)
            updateCoprocessor0Bits(EPC, faultPc - if (inBranchSlot) INSTRUCTION_LENGTH else 0)
        }
        updateCoprocessor0(CAUSE, causeBits)
        updateCoprocessor0(STATUS, Binary.setBit(status, EXCEPTION_LEVEL))
    }

    fun exit() = exit(0)

    fun exit(code: Int) {
        exitCode = if (session.gui.isGuiMode) 0 else code
        environment.exit(exitCode)
    }
}
