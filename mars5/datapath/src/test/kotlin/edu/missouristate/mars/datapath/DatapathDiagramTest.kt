package edu.missouristate.mars.datapath

import edu.missouristate.mars.datapath.DatapathDiagram.Kind
import edu.missouristate.mars.datapath.DatapathDiagram.Palette
import edu.missouristate.mars.datapath.DatapathDiagram.Wire
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DatapathDiagramTest {
    // The fingerprints cover every wire, target, color, and effective lookup from the old diagrams.
    @ParameterizedTest
    @CsvSource(
        "MAIN, 102, 6, 27d30f44ed520e8c6bdfbcaef3b077e191281ef971d57b35f7e827c429aaa596",
        "REGISTERS, 62, 6, bdacd05663ecc53b7ab53c4dc0c180e19445ef7d53055a01df017e74a5c576c7",
        "CONTROL, 81, 6, 3a8998d2f07c2810de16630e0444a098d36da07e9af15caf0d3eb88188121041",
        "ALU_CONTROL, 24, 11, 76e867b411b45e5525a4e684dc192d0c8620c2529734613178b87d8e573a3ef8",
    )
    fun retainsEveryDiagramValue(kind: Kind, wireCount: Int, paletteCount: Int, expected: String) {
        val diagram = DatapathDiagram.forKind(kind)
        assertEquals(wireCount, diagram.wires.size)
        val lines = buildList {
            for (wire in diagram.wires) {
                assertTrue(wire.targets.all { it in 0 until wireCount })
                assertEquals(paletteCount, wire.colors.size)
                for ((index, color) in wire.colors.withIndex()) {
                    assertEquals(color, wire.rgb(Palette.entries[index]))
                }
                add(listOf(
                    wire.name, wire.horizontal, wire.axis, wire.start, wire.end, wire.label,
                    wire.targets.joinToString(","), wire.colors.joinToString(","),
                ).joinToString("\t"))
            }
            for (lookup in listOf(diagram.opcodes, diagram.functions, diagram.registers)) {
                add("")
                for ((key, name) in lookup.toSortedMap()) add("$key\t$name")
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val canonical = lines.joinToString("\n").toByteArray()
        assertEquals(expected, HexFormat.of().formatHex(digest.digest(canonical)))
    }

    @Test
    fun copiesAndProtectsDiagramAndWireCollections() {
        val targets = mutableListOf(0)
        val colors = mutableListOf(0x123456)
        val wire = Wire("signal", true, 2, 5, 8, false, targets, colors)
        val wires = mutableListOf(wire)
        val lookup = mutableMapOf("000000" to "zero")
        val diagram = DatapathDiagram(wires, lookup, lookup, lookup)
        targets.clear()
        colors.clear()
        wires.clear()
        lookup.clear()

        assertEquals(listOf(wire), diagram.wires)
        assertEquals(listOf(0), wire.targets)
        assertEquals(listOf(0x123456), wire.colors)
        for (map in listOf(diagram.opcodes, diagram.functions, diagram.registers)) {
            assertEquals(mapOf("000000" to "zero"), map)
            assertThrows<UnsupportedOperationException> { (map as MutableMap).clear() }
        }
        assertThrows<UnsupportedOperationException> { (diagram.wires as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (wire.targets as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (wire.colors as MutableList).clear() }
    }
}
