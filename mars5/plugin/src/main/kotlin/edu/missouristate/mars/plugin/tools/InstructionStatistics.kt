package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.J_FORMAT

/** Classifies completed instructions, using the same per-execution stream as Instruction Counter. */
internal class InstructionStatistics : InstructionCounts {
    enum class Category(val messageKey: String) {
        ALU("statistics.alu"), JUMP("statistics.jump"), BRANCH("statistics.branch"),
        MEMORY("statistics.memory"), OTHER("counter.other"),
    }

    private var connected = true
    private val counts = LongArray(Category.entries.size)

    @Synchronized
    fun record(word: Int, instruction: BasicInstruction?) {
        if (connected) counts[category(word, instruction).ordinal]++
    }

    @Synchronized
    override fun countsSnapshot() = InstructionCountsSnapshot(connected, counts.toList())

    @Synchronized
    override fun setConnected(value: Boolean) {
        connected = value
    }

    @Synchronized
    override fun reset() {
        counts.fill(0)
    }

    private fun category(word: Int, instruction: BasicInstruction?): Category {
        if (instruction == null) return Category.OTHER
        // Catalog formats cover conditional branches, including REGIMM and Coprocessor 1.
        when (instruction.instructionFormat) {
            I_BRANCH_FORMAT -> return Category.BRANCH
            J_FORMAT -> return Category.JUMP
            else -> Unit
        }
        val function = word and 0x3f
        return when (word ushr 26) {
            0x00 -> when (function) {
                0x08, 0x09 -> Category.JUMP // jr, jalr
                in aluFunctions -> Category.ALU
                else -> Category.OTHER
            }
            in aluOpcodes -> Category.ALU
            in memoryOpcodes -> Category.MEMORY
            else -> Category.OTHER
        }
    }

    private companion object {
        val aluFunctions = (0x02..0x07).toSet() + (0x10..0x2b) + 0x00
        val aluOpcodes = (0x08..0x0f).toSet() + 0x1c
        val memoryOpcodes = (0x20..0x26).toSet() + (0x28..0x2e) +
            setOf(0x30, 0x31, 0x35, 0x38, 0x39, 0x3d)
    }
}
