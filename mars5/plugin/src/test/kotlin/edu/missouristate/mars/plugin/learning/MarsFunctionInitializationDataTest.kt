package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionInitializationDataTest {
  private val profile =
      """{"formatVersion":7,"cases":[{"name":"partial sums","instructionLimit":1000,"calls":[{"entry":"product","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000001"}}],"initializedOutput":[{"at":{"label":"output","offset":8},"length":16}]}]}"""

  private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject

  private fun JsonObject.range() = row().getAsJsonArray("initializedOutput")[0].asJsonObject

  private fun parse(edit: JsonObject.() -> Unit = {}) =
      MarsFunctionCaseData.parse(
          JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray(),
      )

  @Test
  fun `version seven captures immutable byte ranges with zero size and omitted defaults`() {
    val ranges = parse().single().initializedOutput
    assertEquals("output", ranges.single().at.label)
    assertEquals(8, ranges.single().at.offset)
    assertEquals(16, ranges.single().byteLength)
    assertThrows<UnsupportedOperationException> { (ranges as MutableList).clear() }
    val empty = parse {
      range().addProperty("length", 0)
      range().getAsJsonObject("at").remove("offset")
    }
        .single()
    assertEquals(0, empty.initializedOutput.single().byteLength)
    assertEquals(0, empty.initializedOutput.single().at.offset)
    assertTrue(parse { row().remove("initializedOutput") }.single().initializedOutput.isEmpty())
    assertTrue(
        parse { row().add("initializedOutput", JsonArray()) }.single().initializedOutput.isEmpty(),
    )
  }

  @Test
  fun `previous versions reject new fields while their existing data remains valid`() {
    for (version in 1..6) {
      assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
      assertTrue(
          parse {
            addProperty("formatVersion", version)
            row().remove("initializedOutput")
          }
              .single()
              .initializedOutput
              .isEmpty(),
      )
    }
    val inherited = parse {
      row()
          .getAsJsonArray("calls")[0]
          .asJsonObject
          .add("expectedAddresses", JsonParser.parseString("""{"v1":{"label":"output"}}"""))
      row()
          .getAsJsonArray("calls")[0]
          .asJsonObject
          .add(
              "addressMemory",
              JsonParser.parseString("""[{"at":{"label":"pointer"},"value":{"label":"output"}}]"""),
          )
    }
        .single()
        .calls
        .single()
    assertEquals("output", inherited.expectedAddresses.getValue("v1").label)
    assertEquals("pointer", inherited.addressMemory.single().at.label)
  }

  @Test
  fun `malformed ranges unknown fields and noninteger lengths reject before execution`() {
    for (encoded in
        listOf(
            "null",
            "{}",
            "true",
            "[null]",
            "[{}]",
            "[{\"at\":{\"label\":\"x\"}}]",
            "[{\"length\":1}]",
            "[{\"at\":null,\"length\":1}]",
            "[{\"at\":{\"label\":\"../x\"},\"length\":1}]",
        )) {
      assertThrows<IllegalArgumentException> {
        parse { row().add("initializedOutput", JsonParser.parseString(encoded)) }
      }
    }
    for (length in listOf("-1", "1048577", "1.0", "1e0", "\"1\"", "true", "null")) {
      assertThrows<IllegalArgumentException> {
        parse { range().add("length", JsonParser.parseString(length)) }
      }
    }
    assertThrows<IllegalArgumentException> { parse { range().addProperty("script", "check.kts") } }
    assertThrows<IllegalArgumentException> {
      parse { range().getAsJsonObject("at").addProperty("predicate", "true") }
    }
    assertThrows<IllegalArgumentException> {
      parse { range().getAsJsonObject("at").addProperty("offset", 1048577) }
    }
  }

  @Test
  fun `case aggregate bounds limit both range count and bitmap storage`() {
    assertThrows<IllegalArgumentException> {
      parse {
        row()
            .add(
                "initializedOutput",
                JsonArray().apply {
                  repeat(65) {
                    add(JsonParser.parseString("""{"at":{"label":"output"},"length":0}"""))
                  }
                },
            )
      }
    }
    assertThrows<IllegalArgumentException> {
      parse {
        range().addProperty("length", 1048576)
        row()
            .getAsJsonArray("initializedOutput")
            .add(JsonParser.parseString("""{"at":{"label":"other"},"length":1}"""))
      }
    }
  }
}
