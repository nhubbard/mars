package edu.missouristate.mars.plugin.tools.xray

import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome.NOT_TAKEN
import edu.missouristate.mars.api.StepEvent.BranchOutcome.TAKEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XRayTraceTest {
    @Test
    fun `bounded history includes repeated PCs and rewinds by execution step`() {
        val trace = XRayTrace()
        assertTrue(trace.snapshot().isEmpty())
        val capacity = XRayTrace.capacity
        for (step in 1..capacity + 2) trace.record(StepEvent(0x00400000, 0, step.toLong()))
        val original = trace.snapshot()
        assertSame(original, trace.snapshot())
        assertEquals((3L..(capacity + 2).toLong()).toList(), original.map(StepEvent::stepCount))
        trace.rewind(4)
        assertEquals(listOf(3L, 4L), trace.snapshot().map(StepEvent::stepCount))
        assertEquals(capacity, original.size)
        trace.record(StepEvent(0x00400004, 1, 5))
        assertEquals(listOf(3L, 4L, 5L), trace.snapshot().map(StepEvent::stepCount))
        trace.rewind(2)
        assertTrue(trace.snapshot().isEmpty())
        trace.rewind(0)
        assertTrue(trace.snapshot().isEmpty())
    }

    @Test
    fun `instruction details distinguish core encoding formats and observed branch outcomes`() {
        val add = XRayInstruction(StepEvent(0x00400000, 0x012a4020, 1))
        assertTrue(add.describe().contains("0x00400000 · 0x012a4020 · add"))
        assertTrue(add.describe().contains("rd 01000"))
        assertEquals("add", add.signalLabel("ALUVALUE"))
        assertEquals("Reading", add.signalLabel("READING"))
        assertEquals("Writing", add.signalLabel("WRITING"))
        assertEquals("Other", add.signalLabel("Other"))

        val branch = 0x1100_0001
        val taken = XRayInstruction(StepEvent(0x00400004, branch, 2, null, TAKEN))
        assertTrue(taken.describe().contains("immediate 0000000000000001"))
        assertTrue(taken.describe().contains("Branch: Taken"))
        assertEquals("PC + offset", taken.signalLabel("CP+4"))
        val notTaken = XRayInstruction(StepEvent(0x00400004, branch, 2, null, NOT_TAKEN))
        assertTrue(notTaken.describe().contains("Branch: Not taken"))
        assertEquals("PC + 4", notTaken.signalLabel("CP+4"))
        assertTrue(XRayInstruction(StepEvent(0x00400000, 0x2408_0001, 1)).describe().contains("immediate"))
        assertTrue(XRayInstruction(StepEvent(0x00400008, 0x0810_0000, 3)).describe().contains("target index"))
        val unknown = XRayInstruction(StepEvent(0x0040000c, -1, 4))
        assertTrue(unknown.describe().endsWith("1".repeat(32)))
        assertEquals("Unknown instruction", unknown.signalLabel("ALUVALUE"))
    }

    @Test
    fun `playback pauses resumes restarts and stops at the end of a diagram`() {
        val player = XRayPlayback()
        player.show(30)
        assertEquals(30, player.position)
        assertFalse(player.advance(10))
        player.toggle()
        assertTrue(player.advance(7))
        assertEquals(7, player.position)
        player.toggle()
        assertFalse(player.advance(9))
        assertEquals(7, player.position)
        player.toggle()
        assertTrue(player.advance(32))
        assertEquals(30, player.position)
        assertFalse(player.playing)
        player.toggle()
        assertTrue(player.playing)
        player.reset()
        assertFalse(player.playing)
        assertEquals(0, player.position)
    }
}
