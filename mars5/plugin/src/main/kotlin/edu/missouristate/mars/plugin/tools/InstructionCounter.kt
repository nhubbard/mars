package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.J_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

internal data class InstructionCountsSnapshot(val connected: Boolean, val counts: List<Long>) {
    val total = counts.sum()

    fun percent(count: Long): Int = if (total == 0L) 0 else (count * 100.0 / total).toInt()
}

internal interface InstructionCounts {
    fun countsSnapshot(): InstructionCountsSnapshot
    fun setConnected(value: Boolean)
    fun reset()
}

/** Counts forward simulator events. Undo retains earlier events. Re-executing an instruction adds another event. */
internal class InstructionCounter : InstructionCounts {
    data class Snapshot(val connected: Boolean, val r: Long, val i: Long, val j: Long, val other: Long) {
        val total = r + i + j + other
    }

    private var connected = true
    private var r = 0L
    private var i = 0L
    private var j = 0L
    private var other = 0L

    @Synchronized
    fun record(instruction: BasicInstruction?) {
        if (!connected) return
        when (instruction?.instructionFormat) {
            R_FORMAT -> r++
            I_FORMAT, I_BRANCH_FORMAT -> i++
            J_FORMAT -> j++
            null -> other++
        }
    }

    @Synchronized
    fun snapshot() = Snapshot(connected, r, i, j, other)

    @Synchronized
    override fun countsSnapshot() = InstructionCountsSnapshot(connected, listOf(r, i, j, other))

    @Synchronized
    override fun setConnected(value: Boolean) {
        connected = value
    }

    @Synchronized
    override fun reset() {
        r = 0
        i = 0
        j = 0
        other = 0
    }
}
