package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionTraceCapacityDataTest {
  private val policy =
      """{"from":{"label":"data"},"until":{"label":"end","offset":4},"readable":[{"at":{"label":"data"},"length":4}],"writable":[],"maxAccesses":200000}"""
  private val content =
      """{"formatVersion":12,"cases":[{"name":"CRC history","instructionLimit":3000000,"initialRegisterAddresses":{"sp":{"label":"workspace","offset":256}},"accessPolicies":[$policy],"calls":[{"entry":"function","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"accessPolicies":[$policy]}]}]}"""

  private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject

  private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject

  private fun JsonObject.policy() = getAsJsonArray("accessPolicies")[0].asJsonObject

  private fun parse(edit: JsonObject.() -> Unit = {}) =
      MarsFunctionCaseData.parse(
              JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray(),
          )
          .single()

  @Test
  fun `format twelve keeps distinct scoped capacities and inherited large budgets and addresses`() {
    val case = parse { call().policy().addProperty("maxAccesses", 70_123) }
    assertEquals(200_000, case.accessPolicies.single().maxAccesses)
    assertEquals(70_123, case.calls.single().accessPolicies.single().maxAccesses)
    assertEquals(3_000_000L, case.instructionLimit)
    assertEquals(256, case.initialRegisterAddresses.getValue("sp").offset)
    val omitted = parse {
      row().policy().remove("maxAccesses")
      call().policy().remove("maxAccesses")
    }
    assertEquals(65_536, omitted.accessPolicies.single().maxAccesses)
    assertEquals(65_536, omitted.calls.single().accessPolicies.single().maxAccesses)
  }

  @Test
  fun `previous formats reject explicit history controls even with the legacy default`() {
    for (version in 8..11) assertThrows<IllegalArgumentException> {
      parse {
        addProperty("formatVersion", version)
        row().addProperty("instructionLimit", 1000)
        row().remove("initialRegisterAddresses")
        call().remove("accessPolicies")
        row().policy().addProperty("maxAccesses", 65_536)
      }
    }
    for (version in 9..11) assertThrows<IllegalArgumentException> {
      parse {
        addProperty("formatVersion", version)
        row().addProperty("instructionLimit", 1000)
        row().remove("initialRegisterAddresses")
        row().policy().remove("maxAccesses")
        call().policy().addProperty("maxAccesses", 65_536)
      }
    }
  }

  @Test
  fun `strict integer histories enforce positive per-policy and combined bounds at both scopes`() {
    for (bad in listOf("null", "[]", "true", "\"200000\"", "1.0", "1e3", "-1", "0", "1000001")) {
      for (callScope in listOf(false, true)) assertThrows<IllegalArgumentException> {
        parse {
          (if (callScope) call() else row())
              .policy()
              .add("maxAccesses", JsonParser.parseString(bad))
        }
      }
    }
    for (capacity in listOf(1, 65_536, 200_000, 1_000_000)) {
      val case = parse {
        row().policy().addProperty("maxAccesses", capacity)
        call().policy().addProperty("maxAccesses", capacity)
      }
      assertEquals(capacity, case.accessPolicies.single().maxAccesses)
      assertEquals(capacity, case.calls.single().accessPolicies.single().maxAccesses)
    }
    for (callScope in listOf(false, true)) {
      fun many(count: Int, capacity: Int) = parse {
        val target = if (callScope) call() else row()
        val template = target.policy().deepCopy().apply { addProperty("maxAccesses", capacity) }
        target.add(
            "accessPolicies",
            JsonArray().apply { repeat(count) { add(template.deepCopy()) } },
        )
      }
      many(64, 65_536)
      many(4, 1_000_000)
      assertThrows<IllegalArgumentException> { many(5, 1_000_000) }
    }
  }
}
