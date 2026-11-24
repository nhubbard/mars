package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionRepeatedMemoryDataTest {
  private fun profile() =
      JsonParser.parseString(
              """{"formatVersion":19,"cases":[{"name":"repeated bytes","instructionLimit":1000,"initialMemory":[{"label":"input","bytes":"a5","repeat":12}],"exit":{"memory":[{"label":"output","bytes":"1234","repeat":3}]},"calls":[{"entry":"function","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"setupMemory":[{"label":"input","bytes":"0031","offset":1,"repeat":4}],"memory":[{"label":"output","bytes":"1234","repeat":3}]}]}]}""",
          )
          .asJsonObject

  private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject

  private fun parse(edit: JsonObject.() -> Unit = {}) =
      MarsFunctionCaseData.parse(profile().apply(edit).toString().toByteArray()).single()

  @Test
  fun `format nineteen retains lossless repeats at setup result and exit scopes`() {
    val case = parse()
    assertEquals(12, case.initialMemory.single().byteLength)
    assertEquals(8, case.calls.single().setupMemory.single().byteLength)
    assertEquals(6, case.calls.single().memory.single().byteLength)
    assertEquals(6, case.exit!!.memory.single().byteLength)
    assertEquals(1, case.calls.single().setupMemory.single().offset)
  }

  @Test
  fun `old versions reject the new field even for a single repetition`() {
    for (version in 1..18) {
      assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
    }
  }

  @Test
  fun `repeat remains an exact positive bounded integer and counts expanded bytes`() {
    for (bad in
        listOf("null", "true", "[]", "\"2\"", "2.0", "2e2", "0", "-1", "1048577", "2147483647")) {
      assertThrows<IllegalArgumentException> {
        parse {
          row()
              .getAsJsonArray("initialMemory")[0]
              .asJsonObject
              .add("repeat", JsonParser.parseString(bad))
        }
      }
    }
    assertThrows<IllegalArgumentException> {
      parse {
        row().getAsJsonArray("initialMemory")[0].asJsonObject.apply {
          addProperty("bytes", "0001")
          addProperty("repeat", 524289)
        }
      }
    }
  }
}
