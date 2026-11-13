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
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.VADDR
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.Instruction.Companion.INSTRUCTION_LENGTH
import edu.missouristate.mars.mips.instructions.SyscallRegistry
import edu.missouristate.mars.mips.instructions.syscalls.RandomStreams
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION
import edu.missouristate.mars.util.Binary

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

    /** Resolve a register-based address against the existing 32-bit memory map. */
    internal fun effectiveAddress(register: Int, offset: Int, exception: Int = ADDRESS_EXCEPTION_LOAD): Int {
        if (session.target == MarsTarget.MARS32) return registerFile.getValue(register) + offset
        val address = registerFile.getLongValue(register) + offset.toShort()
        val mapped = address.toInt()
        if (address != mapped.toLong() && address != mapped.toUInt().toLong()) {
            val hex = address.toULong().toString(16).padStart(16, '0')
            throw AddressErrorException("Address 0x$hex is outside mapped memory; low word ", exception, mapped)
        }
        return mapped
    }

    internal fun registerJumpTarget(statement: ProgramStatement, register: Int, offset: Int = 0): Int =
        try {
            effectiveAddress(register, offset)
        } catch (failure: AddressErrorException) {
            throw ProcessingException(this, statement, failure)
        }

    internal fun jumpAndLinkRegister(statement: ProgramStatement, link: Int, source: Int) {
        if (session.target == MarsTarget.MARS32) {
            linkReturnAddress(link)
            jump(registerFile.getValue(source))
        } else {
            val target = registerJumpTarget(statement, source)
            linkReturnAddress(link)
            jump(target)
        }
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

    fun setProgramCounter(value: Int): Int {
        val previous = registerFile.setProgramCounter(value)
        if (session.backStepper.enabled()) session.backStepper.addPCRestore(previous)
        return previous
    }

    /** Save the address after the jump or its delay slot, as configured for this session. */
    internal fun linkReturnAddress(register: Int) {
        val delay = if (settings().isDelayedBranchingEnabled) INSTRUCTION_LENGTH else 0
        link(register, registerFile.programCounter + delay)
    }

    /** Write the link register and record a call if the register accepts writes. */
    fun link(register: Int, returnAddress: Int) {
        updateRegister(register, returnAddress)
        if (register != 0) lastTransfer = Call(register, returnAddress)
    }

    fun lastTransfer(): Transfer? = lastTransfer

    fun lastBranchOutcome(): BranchOutcome? = lastBranchOutcome

    fun branch(displacement: Int) {
        val target = registerFile.programCounter + (displacement shl 2)
        lastBranchOutcome = BranchOutcome.TAKEN
        if (settings().isDelayedBranchingEnabled) {
            delayedBranch.register(target)
        } else {
            setProgramCounter(target)
        }
    }

    /** Execute or schedule a jump. Linking instructions have already recorded a call. */
    fun jump(target: Int) {
        val withoutLink = lastTransfer == null
        if (settings().isDelayedBranchingEnabled) {
            delayedBranch.register(target, withoutLink)
        } else {
            setProgramCounter(target)
            if (withoutLink) lastTransfer = Jump(target)
        }
    }

    /** Transfer control immediately, even when legacy branch delay slots are enabled. */
    internal fun compactTransfer(statement: ProgramStatement, target: Int, branch: Boolean = false) {
        checkCompactTransferSlot(statement)
        setProgramCounter(target)
        if (branch) lastBranchOutcome = BranchOutcome.TAKEN
        else if (lastTransfer == null) lastTransfer = Jump(target)
    }

    internal fun checkCompactTransferSlot(statement: ProgramStatement) {
        if (delayedBranch.isTriggered) {
            throw ProcessingException(this, statement, "compact control transfer in a delay slot", RESERVED_INSTRUCTION_EXCEPTION)
        }
    }

    fun returnFromException() {
        setProgramCounter(coprocessor0.getValue(EPC))
        lastTransfer = ExceptionReturn.INSTANCE
    }

    fun updateCoprocessor0(number: Int, value: Int): Int {
        val previous = coprocessor0.updateRegister(number, value)
        if (session.backStepper.enabled()) {
            when (number) {
                VADDR, STATUS, CAUSE, EPC -> session.backStepper.addCoprocessor0Restore(number, previous)
            }
        }
        return previous
    }

    fun updateCoprocessor1(number: Int, value: Int): Int {
        val previous = coprocessor1.updateRegister(number, value)
        if (number in 0..31 && session.backStepper.enabled()) {
            session.backStepper.addCoprocessor1Restore(number, previous)
        }
        return previous
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

    fun newErrorList(): ErrorList = ErrorList(session.settings)

    private fun execute(statement: ProgramStatement, dispatchHandler: Boolean) {
        AccessOrigin.duringExecution<Unit, ProcessingException> {
            executeInstruction(statement, dispatchHandler)
        }
    }

    private fun executeInstruction(statement: ProgramStatement, dispatchHandler: Boolean) {
        lastTransfer = null
        lastBranchOutcome = null
        val history = session.backStepper
        val recording = history.beginInstruction(registerFile.programCounter, statement)
        try {
            registerFile.incrementPC()
            try {
                val interrupt = session.takeExternalInterrupt()
                if (interrupt != 0) throw ProcessingException(this, statement, "External interrupt", interrupt)
                val instruction = statement.instruction as? BasicInstruction
                    ?: throw ProcessingException(this, statement, "invalid instruction", RESERVED_INSTRUCTION_EXCEPTION)
                instruction.simulationCode.simulate(statement, this)
                if (instruction.instructionFormat == I_BRANCH_FORMAT && lastBranchOutcome == null) {
                    lastBranchOutcome = BranchOutcome.NOT_TAKEN
                }
            } catch (error: ProcessingException) {
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

    fun completeInstruction() {
        if (delayedBranch.isTriggered) {
            val target = delayedBranch.branchTargetAddress
            setProgramCounter(target)
            if (delayedBranch.isJump && lastTransfer == null) lastTransfer = Jump(target)
            delayedBranch.clear()
        } else if (delayedBranch.isRegistered) {
            delayedBranch.trigger()
        }
    }

    fun setExceptionRegisters(cause: Int) {
        updateCoprocessor0(CAUSE, (coprocessor0.getValue(CAUSE) and 0xfffffc83.toInt()) or (cause shl 2))
        updateCoprocessor0(EPC, registerFile.programCounter - INSTRUCTION_LENGTH)
        updateCoprocessor0(STATUS, Binary.setBit(coprocessor0.getValue(STATUS), EXCEPTION_LEVEL))
    }

    fun exit() = exit(0)

    fun exit(code: Int) {
        exitCode = if (session.gui.isGuiMode) 0 else code
        environment.exit(exitCode)
    }
}
