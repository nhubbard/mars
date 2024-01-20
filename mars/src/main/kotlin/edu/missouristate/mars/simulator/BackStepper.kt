/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

@file:Suppress("NAME_SHADOWING")

package edu.missouristate.mars.simulator

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.Coprocessor0
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.Instruction
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * Used to "step backward" through execution, undoing each instruction.
 */
class BackStepper {
    enum class UndoActions(val rawValue: Int) {
        EMPTY(-1),
        MEMORY_RESTORE_RAW_WORD(0),
        MEMORY_RESTORE_WORD(1),
        MEMORY_RESTORE_HALF(2),
        MEMORY_RESTORE_BYTE(3),
        REGISTER_RESTORE(4),
        PC_RESTORE(5),
        COPROC0_REGISTER_RESTORE(6),
        COPROC1_REGISTER_RESTORE(7),
        COPROC1_CONDITION_CLEAR(8),
        COPROC1_CONDITION_SET(9),
        DO_NOTHING(10);

        companion object {
            @JvmStatic
            fun fromInt(rawValue: Int): UndoActions =
                entries.firstOrNull { it.rawValue == rawValue } ?:
                    throw IllegalArgumentException("Invalid raw value!")
        }
    }

    companion object {
        private const val NOT_PC_VALUE = -1
    }

    var isEnabled = true
    private var backSteps = BackstepStack(Globals.maximumBacksteps)

    /**
     * Check whether there are steps that can be undone.
     */
    fun isEmpty(): Boolean = backSteps.isEmpty()

    /**
     * Determine whether the next back-step action occurred as a result of an instruction that executed in the
     * "delay slot" of a delayed branch.
     */
    fun inDelaySlot() = !isEmpty() && backSteps.peek().inDelaySlot

    /**
     * Carry out a backstep, which undoes the latest execution step.
     * Does nothing if backstepping is disabled, or there are no steps to undo.
     */
    fun backStep() {
        if (isEnabled && !backSteps.isEmpty()) {
            val statement = backSteps.peek().ps
            // Ensures that method call in switch will not result in a new action on the stack
            isEnabled = false
            do {
                val step = backSteps.pop()
                if (step.pc != NOT_PC_VALUE) RegisterFile.setProgramCounter(step.pc)
                try {
                    when (step.action) {
                        UndoActions.MEMORY_RESTORE_RAW_WORD -> Globals.memory.setRawWord(step.param1, step.param2)
                        UndoActions.MEMORY_RESTORE_WORD -> Globals.memory.setWord(step.param1, step.param2)
                        UndoActions.MEMORY_RESTORE_HALF -> Globals.memory.setHalf(step.param1, step.param2)
                        UndoActions.MEMORY_RESTORE_BYTE -> Globals.memory.setByte(step.param1, step.param2)
                        UndoActions.REGISTER_RESTORE -> RegisterFile.updateRegister(step.param1, step.param2)
                        UndoActions.PC_RESTORE -> RegisterFile.setProgramCounter(step.param1)
                        UndoActions.COPROC0_REGISTER_RESTORE -> Coprocessor0.updateRegister(step.param1, step.param2)
                        UndoActions.COPROC1_REGISTER_RESTORE -> Coprocessor1.updateRegister(step.param1, step.param2)
                        UndoActions.COPROC1_CONDITION_CLEAR -> Coprocessor1.clearConditionFlag(step.param1)
                        UndoActions.COPROC1_CONDITION_SET -> Coprocessor1.setConditionFlag(step.param1)
                        UndoActions.DO_NOTHING, UndoActions.EMPTY -> {}
                    }
                } catch (e: Exception) {
                    println("Internal MARS error: address exception while attempting to step backwards!")
                    exitProcess(1)
                }
            } while (!backSteps.isEmpty() && statement == backSteps.peek().ps)
            isEnabled = true
        }
    }

    private fun pc(): Int = RegisterFile.programCounter.getValue() - Instruction.INSTRUCTION_LENGTH

    /**
     * Add a new backstep to the stack. The action here is to restore a raw memory word value.
     *
     * @param address The affected memory address.
     * @param value The "restore" value to be stored there.
     * @return The argument value.
     */
    fun addMemoryRestoreRawWord(address: Int, value: Int): Int {
        backSteps.push(UndoActions.MEMORY_RESTORE_RAW_WORD, pc(), address, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack. The action here
     * is to restore a memory word value.
     *
     * @param address The affected memory address.
     * @param value   The "restore" value to be stored there.
     * @return the argument value
     */
    fun addMemoryRestoreWord(address: Int, value: Int): Int {
        backSteps.push(UndoActions.MEMORY_RESTORE_WORD, pc(), address, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to restore a memory half-word value.
     *
     * @param address The affected memory address.
     * @param value   The "restore" value to be stored there, in the low-order half.
     * @return the argument value
     */
    fun addMemoryRestoreHalf(address: Int, value: Int): Int {
        backSteps.push(UndoActions.MEMORY_RESTORE_HALF, pc(), address, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to restore a memory byte value.
     *
     * @param address The affected memory address.
     * @param value   The "restore" value to be stored there, in low-order byte.
     * @return the argument value
     */
    fun addMemoryRestoreByte(address: Int, value: Int): Int {
        backSteps.push(UndoActions.MEMORY_RESTORE_BYTE, pc(), address, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to restore a register file register value.
     *
     * @param register The affected register number.
     * @param value    The "restore" value to be stored there.
     * @return the argument value
     */
    fun addRegisterFileRestore(register: Int, value: Int): Int {
        backSteps.push(UndoActions.REGISTER_RESTORE, pc(), register, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to restore the program counter.
     *
     * @param value The "restore" value to be stored there.
     * @return the argument value
     */
    fun addPCRestore(value: Int): Int {
        val value = value - Instruction.INSTRUCTION_LENGTH
        backSteps.push(UndoActions.PC_RESTORE, value, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to restore a coprocessor 0 register value.
     *
     * @param register The affected register number.
     * @param value    The "restore" value to be stored there.
     * @return the argument value
     */
    fun addCoprocessor0Restore(register: Int, value: Int): Int {
        backSteps.push(UndoActions.COPROC0_REGISTER_RESTORE, pc(), register, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to restore a coprocessor 1 register value.
     *
     * @param register The affected register number.
     * @param value    The "restore" value to be stored there.
     * @return the argument value
     */
    fun addCoprocessor1Restore(register: Int, value: Int): Int {
        backSteps.push(UndoActions.COPROC1_REGISTER_RESTORE, pc(), register, value)
        return value
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to set the given coprocessor 1 condition flag (to 1).
     *
     * @param flag The condition flag number.
     * @return the argument value
     */
    fun addConditionFlagSet(flag: Int): Int {
        backSteps.push(UndoActions.COPROC1_CONDITION_SET, pc(), flag)
        return flag
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to clear the given coprocessor 1 condition flag (to 0).
     *
     * @param flag The condition flag number.
     * @return the argument value
     */
    fun addConditionFlagClear(flag: Int): Int {
        backSteps.push(UndoActions.COPROC1_CONDITION_CLEAR, pc(), flag)
        return flag
    }

    /**
     * Add a new "back step" (the undo action) to the stack.  The action here
     * is to do nothing!  This is just a placeholder, so when the user is backstepping
     * through the program, no instructions will be skipped.  Cosmetic. If the top of the
     * stack has the same PC counter, the do-nothing action will not be added.
     *
     * @return 0
     */
    fun addDoNothing(pc: Int): Int {
        if (backSteps.isEmpty() || backSteps.peek().pc != pc) backSteps.push(UndoActions.DO_NOTHING, pc)
        return 0
    }

    /**
     * Represents a "back step" (undo action) on the stack.
     */
    private class BackStep(var action: UndoActions, var pc: Int, var param1: Int, var param2: Int) {
        var ps: ProgramStatement? = null
        var inDelaySlot: Boolean = false

        fun assign(action: UndoActions, programCounter: Int, param1: Int, param2: Int) {
            this.action = action
            this.pc = programCounter
            try {
                ps = Globals.memory.getStatement(pc, notify = false)
            } catch (e: Exception) {
                ps = null
                pc = NOT_PC_VALUE
            }
            this.param1 = param1
            this.param2 = param2
            inDelaySlot = Simulator.inDelaySlot
        }

        constructor() : this(UndoActions.EMPTY, NOT_PC_VALUE, -1, -1)
    }

    private class BackstepStack(private val capacity: Int) {
        private var size: Int = 0
        private var top: Int = -1
        private val stack: Array<BackStep> = Array(capacity) { BackStep() }

        // Lock to synchronize actions
        private val lock = ReentrantLock()

        fun isEmpty(): Boolean = lock.withLock { return size == 0 }

        @JvmOverloads
        fun push(action: UndoActions, programCounter: Int, param1: Int = 0, param2: Int = 0) = lock.withLock {
            if (size == 0) {
                top = 0
                size++
            } else if (size < capacity) {
                top = (top + 1) % capacity
                size++
            } else {
                top = (top + 1) % capacity
            }
            // We'll re-use existing objects rather than creating/discarding each time.
            // Must use assign() method rather than series of assignment statements!
            stack[top].assign(action, programCounter, param1, param2)
        }

        fun pop(): BackStep = lock.withLock {
            val bs = stack[top]
            top = if (size == 1) -1 else (top + capacity - 1) % capacity
            size--
            return bs
        }

        fun peek(): BackStep = lock.withLock { return stack[top] }
    }
}