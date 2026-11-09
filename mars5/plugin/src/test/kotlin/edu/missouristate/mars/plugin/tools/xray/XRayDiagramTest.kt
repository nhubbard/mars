package edu.missouristate.mars.plugin.tools.xray

import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import java.awt.Color
import java.awt.image.BufferedImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XRayDiagramTest {
    @Test
    fun `bundled diagrams retain their original geometry and reachable signals`() {
        val sizes = listOf(1000 to 574, 800 to 480, 800 to 498, 800 to 457)
        for ((unit, size) in XRayUnit.entries.zip(sizes)) {
            val diagram = XRayDiagram.load(unit)
            assertEquals(size, diagram.image.width to diagram.image.height, unit.name)
            val signals = diagram.signals(0x012a4020, null)
            assertEquals(listOf(101, 62, 81, 24)[unit.ordinal], signals.size, unit.name)
            assertEquals(0, signals.first().startsAt)
            assertTrue(signals.zipWithNext().all { (first, second) -> first.startsAt <= second.startsAt })
        }
    }

    @Test
    fun `animation follows wire direction and starts children after the parent`() {
        val signals = XRayDiagram.load(XRayUnit.DATAPATH).signals(0x012a4020, null)
        val counter = signals.first()
        assertEquals("*Program_counter", counter.name)
        assertEquals(Color.YELLOW, counter.color)
        assertEquals(362, counter.axis)
        assertTrue(counter.horizontal)
        assertEquals(161, counter.positionAt(0))
        assertEquals(171, counter.positionAt(10))
        assertEquals(188, counter.positionAt(50))

        val input = signals.single { it.name == "*nodeADD1input1A" }
        assertFalse(input.horizontal)
        assertEquals(counter.endsAt, input.startsAt)
        assertNull(input.positionAt(input.startsAt - 1))
        assertEquals(358, input.positionAt(input.startsAt))
        assertEquals(357, input.positionAt(input.startsAt + 1))
        assertEquals(95, input.positionAt(input.endsAt))
        assertEquals(setOf("READING", "WRITING", "ALUVALUE", "CP+4"), signals.filter { it.isLabel }.map { it.name }.toSet())
    }

    @Test
    fun `main diagram selects register immediate jump memory and branch colors`() {
        val diagram = XRayDiagram.load(XRayUnit.DATAPATH)
        fun colors(word: Int) = diagram.signals(word, null).map { it.color }
        val palettes = listOf(
            colors(0x012a4020), colors(0x2528_0001), colors(0x0800_0000), colors(0x8d28_0000.toInt()),
            colors(0xad28_0000.toInt()), diagram.signals(0x4501_0000, I_BRANCH_FORMAT).map { it.color },
        )
        assertEquals(6, palettes.distinct().size)
        assertEquals(palettes[2], colors(0x0c00_0000))
        for (opcode in listOf(0x30, 0x31, 0x35)) assertEquals(palettes[3], colors(opcode shl 26))
        for (opcode in listOf(0x38, 0x39, 0x3d)) assertEquals(palettes[4], colors(opcode shl 26))
    }

    @Test
    fun `ALU control selects a palette by function for register instructions`() {
        val diagram = XRayDiagram.load(XRayUnit.ALU_CONTROL)
        fun colors(word: Int) = diagram.signals(word, null).map { it.color }
        val palettes = listOf(0x20, 0x22, 0x24, 0x25, 0x2a).map { colors(0x012a4000 or it) }
        assertEquals(5, palettes.distinct().size)
        assertEquals(palettes[0], colors(0x00220020))
        assertEquals(Color.RED, palettes.last().first())
    }

    @Test
    fun `an invalid vertex ID cannot redirect a wire to the wrong segment`() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val xml = "<diagram><datapath_map><num_vertex>1</num_vertex></datapath_map></diagram>"
        val error = assertThrows(IllegalStateException::class.java) {
            xml.byteInputStream().use { XRayDiagram.read(XRayUnit.DATAPATH, image, it) }
        }
        assertEquals("Invalid vertex IDs in MipsXRayOpcode", error.message)
    }
}
