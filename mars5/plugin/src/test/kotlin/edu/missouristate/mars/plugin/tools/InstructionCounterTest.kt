package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.InstructionCounter.Snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstructionCounterTest {
    @Test
    fun `counts execution formats and preserves each run's connection and reset state`() {
        val instructions = InstructionDefaults.get()
        val counter = InstructionCounter()
        val otherRun = InstructionCounter()
        fun record(word: Int) = counter.record(instructions.findByBinaryCode(word))
        assertEquals(0, counter.countsSnapshot().percent(0))
        record(0x012a4021) // addu
        record(0x24080007) // addiu
        record(0x11000000) // beq
        record(0x08100000) // j
        record(-1) // unknown

        val snapshot = counter.snapshot()
        assertEquals(Snapshot(true, 1, 2, 1, 1), snapshot)
        assertEquals(5, snapshot.total)
        assertEquals(40, counter.countsSnapshot().percent(snapshot.i))
        assertEquals(Snapshot(true, 0, 0, 0, 0), otherRun.snapshot())

        counter.setConnected(false)
        record(0x08100000)
        assertEquals(snapshot.copy(connected = false), counter.snapshot())
        counter.reset()
        assertEquals(Snapshot(false, 0, 0, 0, 0), counter.snapshot())
        counter.setConnected(true)
        record(0x08100000)
        assertEquals(Snapshot(true, 0, 0, 1, 0), counter.snapshot())
    }
}
