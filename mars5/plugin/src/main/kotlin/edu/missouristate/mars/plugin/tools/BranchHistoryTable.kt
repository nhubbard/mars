package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome.TAKEN
import edu.missouristate.mars.mips.instructions.Instruction.INSTRUCTION_LENGTH

internal class BranchHistoryTable {
    data class Options(val size: Int = 16, val historyBits: Int = 1, val initialTaken: Boolean = false) {
        init {
            require(size in sizes)
            require(historyBits in histories)
        }
    }

    data class Row(val history: List<Boolean>, val predictedTaken: Boolean, val correct: Long, val incorrect: Long) {
        val precision: Double get() = if (correct + incorrect == 0L) 0.0 else correct * 100.0 / (correct + incorrect)
    }

    data class Observation(val pc: Int, val target: Int, val index: Int, val predictedTaken: Boolean, val taken: Boolean)
    data class Snapshot(val connected: Boolean, val options: Options, val rows: List<Row>, val recent: List<Observation>)

    private class Entry(var history: Int, var predictedTaken: Boolean, var correct: Long = 0, var incorrect: Long = 0)

    private var connected = true
    private var options = Options()
    private var rows = newRows()
    private val recent = ArrayDeque<Observation>()

    @Synchronized
    fun record(event: StepEvent) {
        val outcome = event.branchOutcome() ?: return
        if (!connected) return
        val index = (event.pc() ushr 2) and (options.size - 1)
        val entry = rows[index]
        val taken = outcome == TAKEN
        val predicted = entry.predictedTaken
        val mask = (1 shl options.historyBits) - 1
        entry.history = ((entry.history shl 1) or if (taken) 1 else 0) and mask
        if (taken == predicted) {
            entry.correct++
        } else {
            entry.incorrect++
            if (entry.history == if (taken) mask else 0) entry.predictedTaken = taken
        }
        if (recent.size == logLimit) recent.removeFirst()
        val target = event.pc() + INSTRUCTION_LENGTH + (event.instruction().toShort().toInt() shl 2)
        recent.addLast(Observation(event.pc(), target, index, predicted, taken))
    }

    @Synchronized
    fun snapshot() = Snapshot(connected, options, rows.map { entry ->
        val history = (options.historyBits - 1 downTo 0).map { bit -> entry.history and (1 shl bit) != 0 }
        Row(history, entry.predictedTaken, entry.correct, entry.incorrect)
    }, recent.toList())

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value
    }

    @Synchronized
    fun configure(value: Options) {
        options = value
        reset()
    }

    @Synchronized
    fun reset() {
        rows = newRows()
        recent.clear()
    }

    private fun newRows() = Array(options.size) {
        val history = if (options.initialTaken) (1 shl options.historyBits) - 1 else 0
        Entry(history, options.initialTaken)
    }

    companion object {
        val sizes = listOf(8, 16, 32)
        val histories = listOf(1, 2)
        private const val logLimit = 128
    }
}
