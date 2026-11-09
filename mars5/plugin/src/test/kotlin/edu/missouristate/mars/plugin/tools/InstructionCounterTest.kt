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
        assertEquals(0, counter.snapshot().percent(0))
        counter.record(0x012a4021, instructions) // addu
        counter.record(0x24080007, instructions) // addiu
        counter.record(0x11000000, instructions) // beq
        counter.record(0x08100000, instructions) // j
        counter.record(-1, instructions) // unknown

        val snapshot = counter.snapshot()
        assertEquals(Snapshot(true, 1, 2, 1, 1), snapshot)
        assertEquals(5, snapshot.total)
        assertEquals(40, snapshot.percent(snapshot.i))
        assertEquals(Snapshot(true, 0, 0, 0, 0), otherRun.snapshot())

        counter.setConnected(false)
        counter.record(0x08100000, instructions)
        assertEquals(snapshot.copy(connected = false), counter.snapshot())
        counter.reset()
        assertEquals(Snapshot(false, 0, 0, 0, 0), counter.snapshot())
        counter.setConnected(true)
        counter.record(0x08100000, instructions)
        assertEquals(Snapshot(true, 0, 0, 1, 0), counter.snapshot())
    }
}
