package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.FunctionMemoryCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsConsolePublicationDataTest {
  private val state =
      """{"memory":[{"label":"used","bytes":"01000000"}],"addressMemory":[{"at":{"label":"pointer"},"value":{"label":"ring","offset":3}}],"accessPolicies":[{"from":{"label":"used"},"until":{"label":"end","offset":4},"writable":[{"at":{"label":"used"},"length":4}],"checkReads":false}]}"""
  private val valid =
      """{"label":"operation_ready","initialMemory":[{"label":"command_count","bytes":"01000000"}],"snapshots":[$state],"finalState":$state}"""

  private fun json(raw: String = valid) = JsonParser.parseString(raw).asJsonObject

  private fun parse(raw: String = valid, version: Int = 7) =
      MarsConsoleCaseData.parse(
              """{"formatVersion":$version,"cases":[{"name":"caller","input":"","stdout":"","instructionLimit":20000,"publication":$raw}]}"""
                  .toByteArray(),
          )
          .single()

  private fun reject(change: JsonObject.() -> Unit) {
    assertThrows<IllegalArgumentException> { parse(json().apply(change).toString()) }
  }

  @Test
  fun `version seven uses the existing strict memory relocation and access policy contracts`() {
    val parsed = parse().publication!!
    assertEquals("operation_ready", parsed.label)
    assertEquals(listOf(FunctionMemoryCheck("command_count", "01000000")), parsed.initialMemory)
    assertEquals(1, parsed.snapshots.size)
    assertEquals("used", parsed.snapshots.single().memory.single().label)
    assertEquals(3, parsed.finalState.addressMemory.single().value.offset)
    assertEquals(false, parsed.finalState.accessPolicies.single().checkReads)
    assertEquals(4, parsed.finalState.accessPolicies.single().writable.single().byteLength)
    for (version in 1..6) assertThrows<IllegalArgumentException> { parse(version = version) }
    val empty = parse(json().apply { add("snapshots", JsonArray()) }.toString()).publication!!
    assertTrue(empty.snapshots.isEmpty())
    val maximum =
        json().apply { add("snapshots", JsonArray().apply { repeat(256) { add(json(state)) } }) }
    assertEquals(256, parse(maximum.toString()).publication!!.snapshots.size)
  }

  @Test
  fun `unknown fields executable predicates malformed states and oversized schedules are rejected`() {
    for (raw in listOf("null", "true", "[]", "7", "\"marker\"")) assertThrows<
        IllegalArgumentException,
    > {
      parse(raw)
    }
    for (field in json().keySet()) reject { remove(field) }
    reject { addProperty("predicate", "execute host code") }
    for (raw in listOf("null", "true", "[]", "7", "\"not a label\"")) reject {
      add("label", JsonParser.parseString(raw))
    }
    reject { add("initialMemory", JsonObject()) }
    reject { add("snapshots", JsonObject()) }
    reject { add("snapshots", JsonArray().apply { repeat(257) { add(json(state)) } }) }
    reject { add("snapshots", JsonArray().apply { add(JsonObject()) }) }
    reject { add("snapshots", JsonArray().apply { add(4) }) }
    for (raw in listOf("null", "true", "[]", "7")) reject {
      add("finalState", JsonParser.parseString(raw))
    }
    for (field in listOf("memory", "addressMemory", "accessPolicies")) {
      reject { getAsJsonObject("finalState").remove(field) }
      reject { getAsJsonObject("finalState").add(field, JsonObject()) }
    }
    reject { getAsJsonObject("finalState").addProperty("callback", "anything") }
    reject {
      getAsJsonObject("finalState")
          .getAsJsonArray("memory")[0]
          .asJsonObject
          .addProperty("bytes", "zz")
    }
    reject {
      getAsJsonObject("finalState")
          .getAsJsonArray("addressMemory")[0]
          .asJsonObject
          .addProperty("address", "0x123")
    }
    reject {
      getAsJsonObject("finalState")
          .getAsJsonArray("accessPolicies")[0]
          .asJsonObject
          .addProperty("checkReads", "false")
    }
    reject {
      getAsJsonArray("initialMemory")[0].asJsonObject.apply {
        addProperty("bytes", "00")
        addProperty("repeat", 1048576)
      }
    }
    val root =
        """{"formatVersion":7,"cases":[{"name":"mixed","input":"","stdout":"","instructionLimit":1,"publication":$valid,"maze":{"kind":"renderer","fixture":0}}]}"""
    assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(root.toByteArray()) }
  }

  @Test
  fun `version eight names ordered boundaries and full-width register observations without broadening older formats`() {
    val root =
        json().apply {
          getAsJsonArray("snapshots")[0].asJsonObject.apply {
            addProperty("label", "blit_ready")
            add(
                "expectedRegisters",
                JsonParser.parseString("""{"v0":"0xffffffffffffffff","v1":"0x0000000100000008"}"""),
            )
          }
          getAsJsonObject("finalState").add("expectedRegisters", JsonObject())
        }
    val result = parse(root.toString(), 8).publication!!
    assertEquals("blit_ready", result.snapshots.single().label)
    assertEquals(
        mapOf("v0" to -1L, "v1" to 0x100000008L),
        result.snapshots.single().expectedRegisters,
    )
    assertTrue(result.finalState.expectedRegisters.isEmpty())
    assertEquals(1, parse(version = 8).publication!!.snapshots.size)
    assertThrows<IllegalArgumentException> { parse(root.toString(), 7) }
    for (field in listOf("label", "expectedRegisters")) {
      val legacy =
          json().apply {
            getAsJsonArray("snapshots")[0]
                .asJsonObject
                .add(field, root.getAsJsonArray("snapshots")[0].asJsonObject.get(field))
          }
      assertThrows<IllegalArgumentException> { parse(legacy.toString(), 7) }
    }
    for (bad in listOf("null", "true", "[]", "5", "\"not a label\"")) {
      val invalid =
          root.deepCopy().apply {
            getAsJsonArray("snapshots")[0].asJsonObject.add("label", JsonParser.parseString(bad))
          }
      assertThrows<IllegalArgumentException> { parse(invalid.toString(), 8) }
    }
    for (bad in
        listOf(
            "null",
            "true",
            "[]",
            "5",
            """{"v0":"0x1"}""",
            $$"""{"v0":"0x0000000000000000","$v0":"0x0000000000000000"}""",
        )) {
      val invalid =
          root.deepCopy().apply {
            getAsJsonArray("snapshots")[0]
                .asJsonObject
                .add("expectedRegisters", JsonParser.parseString(bad))
          }
      assertThrows<IllegalArgumentException> { parse(invalid.toString(), 8) }
    }
    assertThrows<IllegalArgumentException> {
      parse(
          root
              .deepCopy()
              .apply { getAsJsonObject("finalState").addProperty("label", "done") }
              .toString(),
          8,
      )
    }
    assertThrows<IllegalArgumentException> {
      parse(
          root
              .deepCopy()
              .apply {
                getAsJsonArray("snapshots")[0].asJsonObject.addProperty("predicate", "run anything")
              }
              .toString(),
          8,
      )
    }
  }
}
