package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.InstructionStatistics.Category
import edu.missouristate.mars.plugin.tools.InstructionStatistics.Category.ALU
import edu.missouristate.mars.plugin.tools.InstructionStatistics.Category.BRANCH
import edu.missouristate.mars.plugin.tools.InstructionStatistics.Category.JUMP
import edu.missouristate.mars.plugin.tools.InstructionStatistics.Category.MEMORY
import edu.missouristate.mars.plugin.tools.InstructionStatistics.Category.OTHER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstructionStatisticsTest {
    @Test
    fun `uses decoded instructions to classify integer and coprocessor operations`() {
        val instructions = InstructionDefaults.get()
        val statistics = InstructionStatistics()
        val cases = listOf(
            0x00000000 to ALU, // nop
            0x00094042 to ALU, // srl
            0x012a4021 to ALU, // addu
            0x24080007 to ALU, // addiu
            0x712a4002 to ALU, // mul
            0x08100000 to JUMP, // j
            0x03e00008 to JUMP, // jr
            0x11000000 to BRANCH, // beq
            0x0500fffe to BRANCH, // bltz with a negative displacement
            0x45010001 to BRANCH, // bc1t
            0x8fa80000.toInt() to MEMORY, // lw
            0xe3a80000.toInt() to MEMORY, // sc
            0xc7a80000.toInt() to MEMORY, // lwc1
            0x0000000c to OTHER, // syscall
            0x05080001 to OTHER, // tgei
            0x40086000 to OTHER, // mfc0
            -1 to OTHER, // unknown encoding
        )
        for ((word, expected) in cases) {
            statistics.reset()
            statistics.record(word, instructions.findByBinaryCode(word))
            val counts = statistics.countsSnapshot()
            assertEquals(Category.entries.map { if (it == expected) 1L else 0L }, counts.counts, word.toUInt().toString(16))
        }
        statistics.setConnected(false)
        statistics.reset()
        statistics.record(0, instructions.findByBinaryCode(0))
        assertEquals(InstructionCountsSnapshot(false, List(5) { 0L }), statistics.countsSnapshot())
        statistics.setConnected(true)
        statistics.record(0, instructions.findByBinaryCode(0))
        assertEquals(InstructionCountsSnapshot(true, listOf(1L, 0L, 0L, 0L, 0L)), statistics.countsSnapshot())
    }
}
