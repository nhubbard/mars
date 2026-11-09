package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.J_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.InstructionSet

/** Counts forward simulator events. Undo keeps past events, and executing an instruction again counts again. */
internal class InstructionCounter {
    data class Snapshot(val connected: Boolean, val r: Long, val i: Long, val j: Long, val other: Long) {
        val total = r + i + j + other

        fun percent(count: Long): Int = if (total == 0L) 0 else (count * 100.0 / total).toInt()
    }

    private var connected = true
    private var r = 0L
    private var i = 0L
    private var j = 0L
    private var other = 0L

    @Synchronized
    fun record(word: Int, instructions: InstructionSet) {
        if (!connected) return
        when (instructions.findByBinaryCode(word)?.instructionFormat) {
            R_FORMAT -> r++
            I_FORMAT, I_BRANCH_FORMAT -> i++
            J_FORMAT -> j++
            null -> other++
        }
    }

    @Synchronized
    fun snapshot() = Snapshot(connected, r, i, j, other)

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value
    }

    @Synchronized
    fun reset() {
        r = 0
        i = 0
        j = 0
        other = 0
    }
}
