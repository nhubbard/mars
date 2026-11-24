package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionInitialAddressDataTest {
  private val content =
      """{"formatVersion":10,"cases":[{"name":"workspace stack","instructionLimit":1000,"initialRegisterAddresses":{"sp":{"label":"workspace","offset":256},"s0":{"label":"input","offset":3}},"calls":[{"entry":"function","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"accessPolicies":[]}]}]}"""

  private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject

  private fun parse(edit: JsonObject.() -> Unit = {}) =
      MarsFunctionCaseData.parse(
              JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray(),
          )
          .single()

  @Test
  fun `format ten retains immutable assembled initial values and inherited call policies`() {
    val case = parse()
    assertEquals(256, case.initialRegisterAddresses.getValue("sp").offset)
    assertEquals("input", case.initialRegisterAddresses.getValue("s0").label)
    assertTrue(case.calls.single().accessPolicies.isEmpty())
    assertThrows<UnsupportedOperationException> {
      (case.initialRegisterAddresses as MutableMap).clear()
    }
    assertTrue(
        parse { row().remove("initialRegisterAddresses") }.initialRegisterAddresses.isEmpty(),
    )
  }

  @Test
  fun `format eleven inherits symbolic setup memory and invocation restrictions at the larger budget`() {
    val policy =
        """[{"from":{"label":"input"},"until":{"label":"end"},"readable":[{"at":{"label":"input"},"length":4}],"writable":[]}]"""
    fun definition(version: Int) = parse {
      addProperty("formatVersion", version)
      row().add("accessPolicies", JsonParser.parseString(policy))
      val call = row().getAsJsonArray("calls")[0].asJsonObject
      call.add("accessPolicies", JsonParser.parseString(policy))
      call.add(
          "argumentAddresses",
          JsonParser.parseString("""{"a0":{"label":"input","offset":1}}"""),
      )
      call.add("expectedAddresses", JsonParser.parseString("""{"v1":{"label":"input"}}"""))
      call.add("memory", JsonParser.parseString("""[{"label":"output","bytes":"0102"}]"""))
      call.add(
          "addressMemory",
          JsonParser.parseString(
              """[{"at":{"label":"pointer"},"value":{"label":"input","offset":1}}]""",
          ),
      )
      if (version == 11) row().addProperty("instructionLimit", 3_000_000)
    }
    val previous = definition(10)
    val larger = definition(11)
    assertEquals(previous.copy(instructionLimit = 3_000_000), larger)
    assertThrows<UnsupportedOperationException> {
      (larger.calls.single().accessPolicies as MutableList).clear()
    }
    assertThrows<UnsupportedOperationException> {
      (larger.initialRegisterAddresses as MutableMap).clear()
    }
  }

  @Test
  fun `older profiles reject the new setup rather than silently ignoring it`() {
    for (version in 1..9) {
      assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
      assertThrows<IllegalArgumentException> {
        parse {
          addProperty("formatVersion", version)
          row().add("initialRegisterAddresses", JsonObject())
        }
      }
      assertTrue(
          parse {
            addProperty("formatVersion", version)
            row().remove("initialRegisterAddresses")
            row().getAsJsonArray("calls")[0].asJsonObject.remove("accessPolicies")
          }
              .initialRegisterAddresses
              .isEmpty(),
      )
    }
  }

  @Test
  fun `strict initial addresses reject executable labels malformed values register conflicts and offsets`() {
    for (value in
        listOf(
            "null",
            "[]",
            "1",
            "true",
            "{\"zero\":{\"label\":\"input\"}}",
            "{\"sp\":{\"label\":\"read(input)\"}}",
            "{\"sp\":{\"label\":\"input\",\"offset\":1048577}}",
            "{\"sp\":{\"label\":\"input\"},\"$" + "sp\":{\"label\":\"input\"}}",
        )) {
      assertThrows<IllegalArgumentException> {
        parse { row().add("initialRegisterAddresses", JsonParser.parseString(value)) }
      }
    }
    assertThrows<IllegalArgumentException> {
      parse {
        row().add("initialRegisters", JsonParser.parseString("""{"sp":"0x0000000000000000"}"""))
      }
    }
    assertThrows<IllegalArgumentException> {
      parse { row().addProperty("initialize", "System.exit(0)") }
    }
  }
}
