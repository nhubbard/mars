package edu.missouristate.mars.simulator

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.Instruction.Companion.INSTRUCTION_LENGTH
import java.util.Set.copyOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.JvmRecord

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Bounded undo history for the instructions and devices of one session. */
open class BackStepper(
    private val memory: MemoryState,
    private val registerFile: IntegerRegisterFile,
    private val coprocessor0: Coprocessor0State,
    private val coprocessor1: Coprocessor1State,
    private val delayedBranch: DelayedBranchState,
    capacity: Int,
) {
    @Volatile private var engaged = true
    private var nextFrame = 0L
    private var activeFrame = 0L
    private var framePc = 0L
    private var frameStatement: ProgramStatement? = null
    private var frameBranch: DelayedBranchState.Snapshot? = null
    private var frameRestore: (() -> Unit)? = null
    private var frameDiscarded = false
    private var frameInDelaySlot = false
    private val backSteps: BackstepStack
    private val checkpoints = CopyOnWriteArrayList<Checkpoint>()

    private class Checkpoint(val capture: () -> (() -> Unit)?) {
        private var previousAction: (() -> Unit)? = null
        private var nextAction: (() -> Unit)? = null
        private var combined: (() -> Unit)? = null

        fun captureAfter(previous: (() -> Unit)?): (() -> Unit)? {
            val next = capture() ?: return previous
            if (previous == null) return next
            if (previousAction !== previous || nextAction !== next) {
                previousAction = previous
                nextAction = next
                combined = {
                    previous()
                    next()
                }
            }
            return combined
        }
    }

    init {
        require(capacity > 0) { "Backstep capacity must be positive" }
        backSteps = BackstepStack(capacity)
    }

    /** Capture device undo before an instruction. Its action runs after CPU and memory restore. */
    open fun addInstructionCheckpoint(capture: () -> (() -> Unit)?): AutoCloseable {
        checkpoints.add(Checkpoint(capture))
        return AutoCloseable {
            // Concurrent closes of duplicate factories must each remove one registration.
            synchronized(checkpoints) {
                checkpoints.firstOrNull { it.capture == capture }?.let(checkpoints::remove)
            }
        }
    }

    /** Open an undo group before incrementing the program counter. */
    open fun beginInstruction(pc: Int, statement: ProgramStatement?): Boolean = beginInstruction(pc.toLong(), statement)

    internal fun beginInstruction(pc: Long, statement: ProgramStatement?): Boolean {
        check(activeFrame == 0L) { "An instruction is already executing" }
        if (!engaged) return false
        val executed = statement ?: throw NullPointerException()
        val reservation = memory.reservation
        // Undo clears an absent reservation directly. A live reservation must be
        // restored before the device checkpoints, as it was captured before them.
        var restore: (() -> Unit)? = if (reservation != null && memory.wideDataEnabled) {
            { memory.reservation = reservation }
        } else null
        for (checkpoint in checkpoints) {
            restore = checkpoint.captureAfter(restore)
        }
        activeFrame = ++nextFrame
        framePc = pc
        frameStatement = executed
        frameBranch = delayedBranch.snapshot()
        frameRestore = restore
        frameInDelaySlot = delayedBranch.isTriggered
        frameDiscarded = false
        return true
    }

    /** Close the undo group, retaining a step for an instruction without writes. */
    open fun endInstruction() {
        if (activeFrame == 0L) return
        try {
            if (engaged && !frameDiscarded && (backSteps.empty() || backSteps.peek().frame != activeFrame)) {
                backSteps.push(Action.DO_NOTHING, framePc)
            }
        } finally {
            activeFrame = 0
            frameStatement = null
            frameBranch = null
            frameRestore = null
            frameDiscarded = false
        }
    }

    open fun clear() {
        check(activeFrame == 0L) { "Cannot clear history during an instruction" }
        backSteps.clear()
    }

    open fun enabled(): Boolean = engaged

    open fun setEnabled(state: Boolean) {
        engaged = state
    }

    open fun empty(): Boolean = backSteps.empty()

    open fun canBackstep(): Boolean = engaged && !empty()

    open fun capacity(): Int = backSteps.capacity

    internal fun previousInstruction(): ProgramStatement? = previousInstructionStep()?.statement

    open fun previousExecution(): ExecutedInstruction? =
        previousInstructionStep()?.let { ExecutedInstruction(it.pc.toInt(), it.inDelaySlot, it.pc) }

    @JvmRecord
    data class ExecutedInstruction @JvmOverloads constructor(
        val address: Int, val delaySlot: Boolean, val addressBits: Long = address.toLong(),
    )

    open fun previousMemoryWrite(): MemoryWrite? = if (engaged) backSteps.previousMemoryWrite() else null

    @JvmRecord
    data class MemoryWrite(val address: Int, val length: Int, val addressBits: Long) {
        constructor(address: Int, length: Int) : this(address, length, address.toUInt().toLong())
    }

    open fun previousRegisterWrites(): Set<RegisterWrite> = if (engaged) backSteps.previousRegisterWrites() else emptySet()

    enum class RegisterWriteBank {
        CPU,
        COPROCESSOR_0,
        COPROCESSOR_1,
        CONDITION_FLAGS,
    }

    @JvmRecord
    data class RegisterWrite(val bank: RegisterWriteBank, val number: Int)

    private fun previousInstructionStep(): BackStep? {
        if (!canBackstep()) return null
        return backSteps.peek().takeIf { it.frame != 0L }
    }

    open fun inDelaySlot(): Boolean = !empty() && backSteps.peek().inDelaySlot

    /** Restore the newest complete group and then restore its attached devices. */
    open fun backStep() {
        if (!canBackstep()) return
        check(activeFrame == 0L) { "Cannot undo an executing instruction" }
        val first = backSteps.peek()
        val statement = first.statement
        val frame = first.frame
        val branch = first.branch
        var restore: (() -> Unit)? = null
        engaged = false
        try {
            do {
                val step = backSteps.pop()
                if (step.restore != null) {
                    restore = step.restore
                    step.restore = null
                }
                if (step.pc != NOT_PC_VALUE) registerFile.setProgramCounterBits(step.pc)
                restoreStep(step)
            } while (!backSteps.empty() && belongsToGroup(backSteps.peek(), frame, statement))
            if (branch != null) delayedBranch.restore(branch)
            if (frame != 0L && memory.wideDataEnabled) memory.reservation = null
            restore?.invoke()
        } catch (error: AddressErrorException) {
            throw IllegalStateException("Cannot restore memory while back-stepping", error)
        } finally {
            engaged = true
        }
    }

    private fun belongsToGroup(step: BackStep, frame: Long, statement: ProgramStatement?): Boolean =
        step.frame == frame && (frame != 0L || step.statement === statement)

    private fun restoreStep(step: BackStep) {
        val first = step.param1
        val second = step.param2.toInt()
        when (step.action) {
            Action.MEMORY_RAW_WORD -> memory.setRawWord(first, second)
            Action.MEMORY_WORD -> memory.setWord(first, second)
            Action.MEMORY_HALF -> memory.setHalf(first, second)
            Action.MEMORY_BYTE -> memory.setByte(first, second)
            Action.MEMORY_WIDE_BYTE, Action.MEMORY_WIDE_HALF, Action.MEMORY_WIDE_WORD ->
                memory.writeData(step.param2, step.action.byteCount, first)
            Action.REGISTER -> registerFile.updateLongRegister(first, step.param2)
            Action.PC -> registerFile.setProgramCounterBits(step.param2)
            Action.COPROCESSOR_0 -> coprocessor0.updateRegisterBits(first, step.param2)
            Action.COPROCESSOR_1 -> if (coprocessor1.hasWideRegisters) {
                coprocessor1.updateLongRegister(first, step.param2)
            } else {
                coprocessor1.updateRegister(first, second)
            }
            Action.VECTOR_HIGH -> coprocessor1.updateVectorHigh(first, step.param2)
            Action.MSA_STATUS -> coprocessor1.updateMsaStatus(second)
            Action.FLOATING_STATUS -> coprocessor1.updateFloatingStatus(second)
            Action.CONDITION_CLEAR -> coprocessor1.clearConditionFlag(first)
            Action.CONDITION_SET -> coprocessor1.setConditionFlag(first)
            Action.HEAP -> memory.heapAddress = first
            Action.DO_NOTHING -> Unit
        }
    }

    private fun pc(): Long = if (activeFrame == 0L) registerFile.programCounterBits - INSTRUCTION_LENGTH else framePc

    open fun addMemoryRestoreRawWord(address: Int, value: Int): Int = record(Action.MEMORY_RAW_WORD, address, value)

    open fun addMemoryRestoreWord(address: Int, value: Int): Int = record(Action.MEMORY_WORD, address, value)

    open fun addMemoryRestoreHalf(address: Int, value: Int): Int = record(Action.MEMORY_HALF, address, value)

    open fun addMemoryRestoreByte(address: Int, value: Int): Int = record(Action.MEMORY_BYTE, address, value)

    internal fun addMemoryRestore(address: Long, size: Int, value: Int) {
        if (address == address.toInt().toLong()) {
            when (size) {
                1 -> addMemoryRestoreByte(address.toInt(), value)
                2 -> addMemoryRestoreHalf(address.toInt(), value)
                else -> addMemoryRestoreWord(address.toInt(), value)
            }
        } else {
            val action = when (size) {
                1 -> Action.MEMORY_WIDE_BYTE
                2 -> Action.MEMORY_WIDE_HALF
                else -> Action.MEMORY_WIDE_WORD
            }
            backSteps.push(action, pc(), value, address)
        }
    }

    open fun addRegisterFileRestore(register: Int, value: Int): Int = addRegisterFileRestore(register, value.toLong()).toInt()

    internal fun addRegisterFileRestore(register: Int, value: Long): Long {
        backSteps.push(Action.REGISTER, pc(), register, value)
        return value
    }

    internal fun addPCRestore(value: Long): Long {
        val address = if (activeFrame == 0L) value - INSTRUCTION_LENGTH else framePc
        backSteps.push(Action.PC, address, second = address)
        return address
    }

    internal fun addCoprocessor0Restore(register: Int, value: Long) {
        backSteps.push(Action.COPROCESSOR_0, pc(), register, value)
    }

    open fun addCoprocessor1Restore(register: Int, value: Int): Int = record(Action.COPROCESSOR_1, register, value)

    internal fun addCoprocessor1Restore(register: Int, value: Long) {
        backSteps.push(Action.COPROCESSOR_1, pc(), register, value)
    }

    private fun record(action: Action, address: Int, value: Int): Int {
        backSteps.push(action, pc(), address, value.toLong())
        return value
    }

    internal fun addVectorHighRestore(register: Int, value: Long) {
        backSteps.push(Action.VECTOR_HIGH, pc(), register, value)
    }

    internal fun addMsaStatusRestore(value: Int) = record(Action.MSA_STATUS, 0, value)

    internal fun addFloatingStatusRestore(value: Int) = record(Action.FLOATING_STATUS, 0, value)

    open fun addConditionFlagSet(flag: Int): Int {
        backSteps.push(Action.CONDITION_SET, pc(), flag)
        return flag
    }

    open fun addConditionFlagClear(flag: Int): Int {
        backSteps.push(Action.CONDITION_CLEAR, pc(), flag)
        return flag
    }

    open fun addHeapRestore(address: Int) {
        backSteps.push(Action.HEAP, pc(), address)
    }

    private enum class Action(val byteCount: Int = 0, val bank: RegisterWriteBank? = null, val wideAddress: Boolean = false) {
        MEMORY_RAW_WORD(Int.SIZE_BYTES),
        MEMORY_WORD(Int.SIZE_BYTES),
        MEMORY_HALF(Short.SIZE_BYTES),
        MEMORY_BYTE(Byte.SIZE_BYTES),
        MEMORY_WIDE_BYTE(Byte.SIZE_BYTES, wideAddress = true),
        MEMORY_WIDE_HALF(Short.SIZE_BYTES, wideAddress = true),
        MEMORY_WIDE_WORD(Int.SIZE_BYTES, wideAddress = true),
        REGISTER(bank = RegisterWriteBank.CPU),
        PC,
        COPROCESSOR_0(bank = RegisterWriteBank.COPROCESSOR_0),
        COPROCESSOR_1(bank = RegisterWriteBank.COPROCESSOR_1),
        VECTOR_HIGH,
        MSA_STATUS,
        FLOATING_STATUS,
        CONDITION_CLEAR(bank = RegisterWriteBank.CONDITION_FLAGS),
        CONDITION_SET(bank = RegisterWriteBank.CONDITION_FLAGS),
        HEAP,
        DO_NOTHING,
    }

    private inner class BackStep {
        var frame = 0L
        var branch: DelayedBranchState.Snapshot? = null
        var restore: (() -> Unit)? = null
        var action = Action.DO_NOTHING
        var pc = 0L
        var statement: ProgramStatement? = null
        var param1 = 0
        var param2 = 0L
        var inDelaySlot = false

        fun assign(action: Action, address: Long, first: Int, second: Long) {
            this.action = action
            frame = activeFrame
            branch = frameBranch
            // The first action carries the device checkpoint and is popped last.
            restore = frameRestore
            frameRestore = null
            pc = address
            if (frame != 0L) {
                pc = framePc
                statement = frameStatement
            } else {
                statement = try {
                    memory.getStatementNoNotify(address)
                } catch (_: Exception) {
                    pc = NOT_PC_VALUE
                    null
                }
            }
            param1 = first
            param2 = second
            inDelaySlot = if (frame != 0L) frameInDelaySlot else delayedBranch.isTriggered
        }
    }

    /** Reuse slots across pushes. Evict a complete instruction when space runs out. */
    private inner class BackstepStack(val capacity: Int) {
        private var size = 0
        private var top = -1
        private val stack = Array(capacity) { BackStep() }

        @Synchronized
        fun clear() {
            size = 0
            top = -1
            for (step in stack) {
                step.statement = null
                step.branch = null
                step.restore = null
                step.frame = 0
            }
        }

        @Synchronized
        fun empty(): Boolean = size == 0

        @Synchronized
        fun previousMemoryWrite(): MemoryWrite? {
            var index = top
            repeat(size) {
                val step = stack[index]
                val length = step.action.byteCount
                if (step.frame != 0L && length > 0) {
                    return if (step.action.wideAddress) MemoryWrite(step.param2.toInt(), length, step.param2)
                    else MemoryWrite(step.param1, length)
                }
                index = previous(index)
            }
            return null
        }

        @Synchronized
        fun previousRegisterWrites(): Set<RegisterWrite> {
            val writes = HashSet<RegisterWrite>()
            val frames = LongArray(RegisterWriteBank.entries.size)
            var banks = 0
            var frame = 0L
            var index = top
            repeat(size) {
                val step = stack[index]
                if (banks == frames.size && step.frame != frame) return copyOf(writes)
                frame = step.frame
                val bank = step.action.bank
                if (frame != 0L && bank != null) {
                    val slot = bank.ordinal
                    if (frames[slot] == 0L) {
                        frames[slot] = frame
                        banks++
                    }
                    if (frames[slot] == frame) {
                        val number = if (bank == RegisterWriteBank.CONDITION_FLAGS) 0 else step.param1
                        writes.add(RegisterWrite(bank, number))
                    }
                }
                index = previous(index)
            }
            return copyOf(writes)
        }

        @Synchronized
        fun push(action: Action, address: Long, first: Int = 0, second: Long = 0) {
            if (!engaged || (activeFrame != 0L && frameDiscarded)) return
            if (size == capacity && activeFrame != 0L) {
                val oldestFrame = stack[oldest()].frame
                if (oldestFrame == activeFrame) {
                    // A single oversized instruction has no complete undo group.
                    clear()
                    frameDiscarded = true
                    return
                }
                do {
                    stack[oldest()].restore = null
                    size--
                } while (size > 0 && oldestFrame != 0L && stack[oldest()].frame == oldestFrame)
            }
            top = if (size == 0 || top == capacity - 1) 0 else top + 1
            if (size < capacity) size++
            stack[top].assign(action, address, first, second)
        }

        @Synchronized
        fun pop(): BackStep {
            val step = stack[top]
            top = if (size == 1) -1 else previous(top)
            size--
            return step
        }

        @Synchronized
        fun peek(): BackStep = stack[top]

        private fun oldest(): Int {
            val index = top - size + 1
            return if (index < 0) index + capacity else index
        }

        private fun previous(index: Int): Int = if (index == 0) capacity - 1 else index - 1
    }

    private companion object {
        const val NOT_PC_VALUE = -1L
    }
}
