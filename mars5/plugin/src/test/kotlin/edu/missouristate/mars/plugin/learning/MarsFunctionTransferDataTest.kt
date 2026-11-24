package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionTransferDataTest {
  private val profile =
      """{"formatVersion":5,"cases":[{"name":"mapped copy","instructionLimit":1000,"calls":[{"entry":"copy","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000002"}}],"transfers":[{"source":{"label":"input","offset":4},"destination":{"label":"output","offset":12},"destinationIndices":[1,0]}]}]}"""

  private fun parse(edit: JsonObject.() -> Unit = {}) =
      MarsFunctionCaseData.parse(
          JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray(),
      )

  private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject

  private fun JsonObject.transfer() = row().getAsJsonArray("transfers")[0].asJsonObject

  @Test
  fun `version five retains immutable relocation-aware maps and older versions reject new semantics`() {
    val maps = parse().single().transfers
    val transfer = maps.single()
    assertEquals("input", transfer.source.label)
    assertEquals(4, transfer.source.offset)
    assertEquals("output", transfer.destination.label)
    assertEquals(12, transfer.destination.offset)
    assertEquals(listOf(1, 0), transfer.destinationIndices)
    assertEquals(4, transfer.elementBytes)
    assertThrows<UnsupportedOperationException> { (maps as MutableList).clear() }
    assertThrows<UnsupportedOperationException> {
      (transfer.destinationIndices as MutableList).clear()
    }
    for (version in 1..4) assertThrows<IllegalArgumentException> {
      parse { addProperty("formatVersion", version) }
    }
    for (version in 1..5) assertTrue(
        parse {
          addProperty("formatVersion", version)
          row().remove("transfers")
        }
            .single()
            .transfers
            .isEmpty(),
    )
    assertTrue(parse { row().add("transfers", JsonArray()) }.single().transfers.isEmpty())
    assertTrue(
        parse { transfer().add("destinationIndices", JsonArray()) }
            .single()
            .transfers
            .single()
            .destinationIndices
            .isEmpty(),
    )
  }

  @Test
  fun `malformed maps addresses dimensions and executable extensions are rejected`() {
    val invalid =
        listOf<JsonObject.() -> Unit>(
            { transfer().remove("source") },
            { transfer().remove("destination") },
            { transfer().remove("destinationIndices") },
            { transfer().addProperty("script", "copy.kts") },
            { transfer().addProperty("predicate", "true") },
            { transfer().getAsJsonObject("source").addProperty("label", "../input") },
            { transfer().getAsJsonObject("destination").addProperty("offset", 1048577) },
            { transfer().getAsJsonObject("source").addProperty("offset", 0.5) },
            { transfer().getAsJsonObject("source").addProperty("length", 4) },
            { transfer().addProperty("elementBytes", 3) },
            { transfer().addProperty("elementBytes", 8) },
            { transfer().add("destinationIndices", JsonParser.parseString("[0,0]")) },
            { transfer().add("destinationIndices", JsonParser.parseString("[2,0]")) },
            { transfer().add("destinationIndices", JsonParser.parseString("[-1]")) },
            { transfer().add("destinationIndices", JsonParser.parseString("[0.0]")) },
            { transfer().add("destinationIndices", JsonParser.parseString("[1e0,0]")) },
            {
              row()
                  .add(
                      "transfers",
                      JsonArray().apply {
                        repeat(33) { add(JsonParser.parseString(profile).asJsonObject.transfer()) }
                      },
                  )
            },
            {
              transfer().add("destinationIndices", JsonArray().apply { repeat(65537) { add(it) } })
            },
        )
    invalid.forEach { edit -> assertThrows<IllegalArgumentException> { parse(edit) } }
    for (value in
        listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive("1"), JsonPrimitive(2))) {
      assertThrows<IllegalArgumentException> { parse { row().add("transfers", value) } }
      assertThrows<IllegalArgumentException> { parse { transfer().add("source", value) } }
      assertThrows<IllegalArgumentException> {
        parse { transfer().add("destinationIndices", value) }
      }
      assertThrows<IllegalArgumentException> {
        parse { transfer().getAsJsonArray("destinationIndices").set(0, value) }
      }
    }
  }
}
