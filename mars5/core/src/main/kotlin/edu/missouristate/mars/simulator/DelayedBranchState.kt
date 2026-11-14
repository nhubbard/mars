package edu.missouristate.mars.simulator

/*
Copyright (c) 2003-2007,  Pete Sanderson and Kenneth Vollmar

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

/**
 * A simulation's pending branch. Registration preserves the first target until the transfer
 * completes. A new branch in the delay slot gives that target one more instruction cycle.
 */
class DelayedBranchState {
    private var state = CLEARED
    private var targetAddress = 0L
    private var jump = false
    internal var forbiddenSlot = false

    /** The pending transfer retained by an instruction's undo group. */
    @JvmRecord
    data class Snapshot @JvmOverloads constructor(
        val state: Int, val targetAddress: Int, val jump: Boolean, val forbiddenSlot: Boolean = false,
        val targetAddressBits: Long = targetAddress.toLong(),
    )

    fun snapshot(): Snapshot = Snapshot(state, targetAddress.toInt(), jump, forbiddenSlot, targetAddress)

    fun restore(snapshot: Snapshot) {
        require(snapshot.state in CLEARED..TRIGGERED) { "Invalid delayed branch state" }
        if (snapshot.state == CLEARED) {
            clear()
        } else {
            state = snapshot.state
            targetAddress = snapshot.targetAddressBits
            jump = snapshot.jump
        }
        forbiddenSlot = snapshot.forbiddenSlot
    }

    fun register(targetAddress: Int) = register(targetAddress, false)

    fun register(targetAddress: Int, jump: Boolean) = register(targetAddress.toLong(), jump)

    internal fun register(targetAddress: Long, jump: Boolean = false) {
        if (state == CLEARED) {
            this.targetAddress = targetAddress
            this.jump = jump
        }
        state = REGISTERED
    }

    /** Mark the end of the branch instruction. The next instruction is its delay slot. */
    fun trigger() {
        if (state != CLEARED) {
            state = TRIGGERED
        }
    }

    fun clear() {
        state = CLEARED
        targetAddress = 0L
        jump = false
        forbiddenSlot = false
    }

    /** Whether completing the pending transfer should report a non-linking jump. */
    val isJump: Boolean get() = jump

    val isRegistered: Boolean get() = state == REGISTERED

    val isTriggered: Boolean get() = state == TRIGGERED

    val branchTargetAddress: Int get() = targetAddress.toInt()

    internal val branchTargetAddressBits: Long get() = targetAddress

    private companion object {
        const val CLEARED = 0
        const val REGISTERED = 1
        const val TRIGGERED = 2
    }
}
