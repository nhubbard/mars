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
import org.junit.jupiter.params.provider.EnumSource

class DatapathDiagramTest {
  // Fingerprints retain every wire, target and color from the original diagrams.
  // Register diagrams also include the explicit correction for the omitted $s6 lookup.
  @ParameterizedTest
  @CsvSource(
      "MAIN, 102, 6, f613178d57fc9d2003357fa35c6066abe040929b3c3f05256e30e7bc5c62020e",
      "REGISTERS, 62, 6, 3842e4c4b416b609d2a8972c99e280c8d01ed05190267295b398bcd3a437816a",
      "CONTROL, 81, 6, f1d92c817007a3b8328aca53ef2cae103b4f937017349cc26f14c93a830bc8c6",
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
        add(
            listOf(
                    wire.name,
                    wire.horizontal,
                    wire.axis,
                    wire.start,
                    wire.end,
                    wire.label,
                    wire.targets.joinToString(","),
                    wire.colors.joinToString(","),
                )
                .joinToString("\t"),
        )
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

  @ParameterizedTest
  @EnumSource(value = Kind::class, names = ["MAIN", "REGISTERS", "CONTROL"])
  fun labelsEveryClassicGeneralPurposeRegister(kind: Kind) {
    val registers = DatapathDiagram.forKind(kind).registers
    val encodings = (0 until 32).map { it.toString(2).padStart(5, '0') }.toSet()
    assertEquals(encodings, registers.keys)
    assertEquals($$"$s6", registers["10110"])
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
