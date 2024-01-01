/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 */

package edu.missouristate.mars.simulator

/**
 * Represents a (potential) delayed branch. Note it is necessary only when
 * delayed branching is enabled. Here's the protocol for using it:
 *
 * 1. When a runtime decision to branch is made (by either a branch or jump
 * instruction simulate() method in InstructionSet) and delayed branching
 * is enabled, the register() method is called with the branch target address, but
 * the program counter won't be set to the branch target address.
 * 2. At the end of that instruction cycle, the simulate() method in [Simulator]
 * will detect the registered branch, and set the trigger. Don't do anything yet
 * because the next instruction cycle is the delay slot and needs to complete.
 * 3. At the end of the next (delay slot) instruction cycle, the simulate()
 * method in Simulator will detect the triggered branch, set the program
 * counter to its target value and clear the delayed branch.
 *
 * The only interesting situation is when the delay slot itself contains a
 * successful branch! I tried this with SPIM (e.g., `beq` followed by `b`)
 * and it treats it as if nothing was there and continues the delay slot
 * into the next cycle. The eventual branch taken is the original one (as one
 * would hope), but in the meantime, the first statement following the sequence
 * of successful branches will constitute the delay slot and will be executed!
 *
 * Since only one pending delayed branch can be taken at a time, everything
 * here is done with statics. The class itself represents the potential branch.
 */
object DelayedBranch {
    enum class State {
        CLEARED,
        REGISTERED,
        TRIGGERED
    }

    @JvmStatic
    private var state = State.CLEARED

    @JvmStatic
    var branchTargetAddress: Int = 0
        private set

    /**
     * Register the fact that a successful branch is to occur. This is called in
     * the instruction's simulated execution (its simulate() method in InstructionSet).
     * If a branch is registered but not triggered, this registration will be ignored
     * (cannot happen if class usage protocol is followed). If a branch is currently
     * registered and triggered, reset the state back to registered (but not triggered)
     * to carry over the delay slot for another execution cycle. This is the
     * only public member of the class.
     *
     * @param targetAddress The address to branch to after executing the next instruction
     */
    @JvmStatic
    fun register(targetAddress: Int) {
        when (state) {
            State.CLEARED -> branchTargetAddress = targetAddress
            State.REGISTERED, State.TRIGGERED -> state = State.REGISTERED
        }
    }

    /**
     * Trigger a registered branch. This is called at the end of the MIPS simulator
     * instruction execution cycle (simulate method in Simulator), so a registered
     * branch will be triggered right away. The next execution cycle will be the
     * delay slot, and at the end of THAT cycle, the trigger will be detected and the
     * branch carried out. This method has package visibility.
     *
     * Precondition: DelayedBranch.isRegistered()
     *
     * Postcondition: DelayedBranch.isTriggered() && !DelayedBranch.isRegistered()
     */
    @JvmStatic
    fun trigger() {
        when (state) {
            State.REGISTERED, State.TRIGGERED -> state = State.TRIGGERED
            else -> {}
        }
    }

    /**
     * Clear the delayed branch. This must be done immediately after setting the
     * program counter to the target address.  This method has package visibility.
     */
    @JvmStatic
    fun clear() {
        state = State.CLEARED
        branchTargetAddress = 0
    }

    @JvmStatic
    val isRegistered: Boolean get() = state == State.REGISTERED

    @JvmStatic
    val isTriggered: Boolean get() = state == State.TRIGGERED
}