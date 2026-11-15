package edu.missouristate.mars.plugin.tools.xray

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome.NOT_TAKEN
import edu.missouristate.mars.api.StepEvent.BranchOutcome.TAKEN
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.plugin.MarsSessionService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class XRayTraceTest {
    @Test
    fun `Release 6 trace decodes a reused opcode and shows all PC bits`() {
        val pc = 0x1_0040_0000L
        val word = 0x212a0001
        val legacy = StepEvent(pc.toInt(), word, 1)
        val wide = legacy.copy(pcBits = pc, target = MarsTarget.MARS64)
        assertTrue(legacy.xRayLabel().contains("addi"))
        assertTrue(wide.xRayLabel().contains("beqc"))
        assertTrue(wide.xRayLabel().contains("0x0000000100400000"))
        val details = XRayInstruction(wide)
        assertTrue(details.describe().contains("beqc"))
        assertTrue(details.isWide)
        assertTrue(details.describe().contains("Encoding fields:"))
        assertEquals(4, XRayWideDiagram.frame(details).signals.size)
    }

    @Test
    fun `MARS64 execution replays vector memory accesses above four GiB`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("xray.mars"), $$"""
            .data 0x100000000
            pixels: .word 1, 2, 3, 4
            .text 0x100400000
            .globl main
            main: dla $t0,pixels
            ld.w $w1,0($t0)
            st.w $w1,16($t0)
        """.trimIndent())
        val trace = XRayTrace()
        MarsSessionService.createMarsComponent().context().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setStartAtMainEnabled(true)
            machine.assemble(listOf(source))
            val simulator = machine.simulator()
            simulator.addListener(object : SimulatorListener {
                override fun onStep(event: StepEvent) = trace.record(event)
                override fun onStop(event: StopEvent) = Unit
                override fun onError(event: ErrorEvent) = Unit
            })
            trace.observeMemory(machine.memory).use {
                assertEquals(StopEvent.Reason.CLIFF_TERMINATION, simulator.runAsync(100, null).get(5, TimeUnit.SECONDS).reason)
            }
        }
        val store = trace.snapshot().last()
        val accesses = trace.memoryFor(store)
        assertTrue(accesses.isNotEmpty())
        assertEquals(0x1_0000_0010L, accesses.first().addressBits)
        assertTrue(accesses.none { it.isInstructionFetch })
        val decoded = XRayInstruction(store, accesses)
        assertTrue(decoded.describe().contains("0x0000000100000010"))
        assertTrue(decoded.describe().contains("MSA vector registers"))
        assertTrue(decoded.describe().contains("Next PC: 0x000000010040"))
        assertEquals(5, XRayWideDiagram.frame(decoded).signals.size)
        trace.rewind(store.stepCount - 1)
        assertTrue(trace.memoryFor(store).isEmpty())
        assertFalse(store in trace.snapshot())
    }

    @Test
    fun `bounded history includes repeated PCs and rewinds by execution step`() {
        val trace = XRayTrace()
        assertTrue(trace.snapshot().isEmpty())
        val capacity = XRayTrace.CAPACITY
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
    fun `disconnect freezes the trace and reconnect drops instructions from the rewound path`() {
        val trace = XRayTrace()
        val first = StepEvent(0x00400000, 0, 1)
        val second = StepEvent(0x00400004, 0, 2)
        val third = StepEvent(0x00400008, 0, 3)
        trace.record(first)
        trace.record(second)
        trace.record(third)
        val original = trace.snapshot()

        trace.setCaptureEnabled(false)
        trace.record(StepEvent(0x0040000c, 0, 4))
        trace.rewind(1)
        trace.record(StepEvent(0x00400010, 0, 2))
        trace.record(StepEvent(0x00400014, 0, 3))
        trace.rewind(2)
        assertSame(original, trace.snapshot())

        trace.setCaptureEnabled(true)
        assertEquals(listOf(first), trace.snapshot())
        val resumed = StepEvent(0x00400014, 0, 3)
        trace.record(resumed)
        assertEquals(listOf(first, resumed), trace.snapshot())
        assertEquals(listOf(first, second, third), original)
    }

    @Test
    fun `clearing preserves capture state and discards any pending rewind`() {
        val trace = XRayTrace()
        trace.record(StepEvent(0x00400000, 0, 1))
        trace.setCaptureEnabled(false)
        trace.rewind(0)
        trace.clear()
        assertFalse(trace.connected)
        val resumed = StepEvent(0x00400004, 0, 2)
        trace.record(resumed)
        assertTrue(trace.snapshot().isEmpty())

        trace.setCaptureEnabled(true)
        trace.record(resumed)
        assertEquals(listOf(resumed), trace.snapshot())
        trace.clear()
        assertTrue(trace.connected)
        assertTrue(trace.snapshot().isEmpty())
        trace.record(resumed)
        assertEquals(listOf(resumed), trace.snapshot())
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
