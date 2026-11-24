package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionAccessPolicyDataTest {
  private val profile =
      """{"formatVersion":8,"cases":[{"name":"caller layout","instructionLimit":1000,"calls":[{"entry":"product","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000001"}}],"accessPolicies":[{"from":{"label":"begin"},"until":{"label":"end","offset":4},"readable":[{"at":{"label":"input","offset":8},"length":16}],"writable":[{"at":{"label":"output"},"length":8}]}]}]}"""

  private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject

  private fun JsonObject.policy() = row().getAsJsonArray("accessPolicies")[0].asJsonObject

  private fun parse(edit: JsonObject.() -> Unit = {}) =
      MarsFunctionCaseData.parse(
          JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray(),
      )

  @Test
  fun `immutable policies retain assembled endpoints offsets and separate access types`() {
    val policies = parse().single().accessPolicies
    val policy = policies.single()
    assertEquals(4, policy.until.offset)
    assertEquals("begin", policy.from.label)
    assertEquals(8, policy.readable.single().at.offset)
    assertEquals(16, policy.readable.single().byteLength)
    assertEquals("output", policy.writable.single().at.label)
    assertEquals(0, policy.writable.single().at.offset)
    assertThrows<UnsupportedOperationException> { (policies as MutableList).clear() }
    assertThrows<UnsupportedOperationException> { (policy.readable as MutableList).clear() }
    assertThrows<UnsupportedOperationException> { (policy.writable as MutableList).clear() }
    val empty = parse {
      policy().remove("readable")
      policy().remove("writable")
    }
        .single()
        .accessPolicies
        .single()
    assertTrue(empty.readable.isEmpty() && empty.writable.isEmpty())
    assertTrue(parse { row().remove("accessPolicies") }.single().accessPolicies.isEmpty())
    assertTrue(parse { row().add("accessPolicies", JsonArray()) }.single().accessPolicies.isEmpty())
    assertTrue(
        parse {
          policy().add("readable", JsonArray())
          policy().add("writable", JsonArray())
        }
            .single()
            .accessPolicies
            .single()
            .readable
            .isEmpty(),
    )
  }

  @Test
  fun `legacy versions reject policies and version eight inherits previous fields`() {
    for (version in 1..7) {
      assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
      assertTrue(
          parse {
            addProperty("formatVersion", version)
            row().remove("accessPolicies")
          }
              .single()
              .accessPolicies
              .isEmpty(),
      )
    }
    val inherited = parse {
      row()
          .add(
              "initializedOutput",
              JsonParser.parseString("""[{"at":{"label":"output"},"length":8}]"""),
          )
      row()
          .getAsJsonArray("calls")[0]
          .asJsonObject
          .add("expectedAddresses", JsonParser.parseString("""{"v1":{"label":"output"}}"""))
    }
        .single()
    assertEquals(8, inherited.initializedOutput.single().byteLength)
    assertEquals("output", inherited.calls.single().expectedAddresses.getValue("v1").label)
  }

  @Test
  fun `strict bounded data rejects malformed intervals windows and executable fields`() {
    for (bad in
        listOf(
            "null",
            "{}",
            "true",
            "[null]",
            "[{}]",
            "[{\"from\":{\"label\":\"x\"}}]",
        )) assertThrows<IllegalArgumentException> {
      parse { row().add("accessPolicies", JsonParser.parseString(bad)) }
    }
    for (bad in
        listOf(
            "null",
            "{}",
            "[null]",
            "[{}]",
            "[{\"at\":{\"label\":\"x\"}}]",
            "[{\"at\":{\"label\":\"x\"},\"length\":-1}]",
            "[{\"at\":{\"label\":\"x\"},\"length\":1048577}]",
            "[{\"at\":{\"label\":\"x\"},\"length\":1.0}]",
            "[{\"at\":{\"label\":\"x\"},\"length\":\"1\"}]",
            "[{\"at\":{\"label\":\"x\"},\"length\":1,\"script\":\"x\"}]",
        )) for (field in listOf("readable", "writable")) assertThrows<IllegalArgumentException> {
      parse { policy().add(field, JsonParser.parseString(bad)) }
    }
    assertThrows<IllegalArgumentException> { parse { policy().addProperty("predicate", "true") } }
    assertThrows<IllegalArgumentException> {
      parse { policy().getAsJsonObject("until").addProperty("offset", 1048577) }
    }
    assertThrows<IllegalArgumentException> { parse { policy().remove("from") } }
  }

  @Test
  fun `combined windows and cases enforce aggregate limits`() {
    assertThrows<IllegalArgumentException> {
      parse {
        policy()
            .add(
                "readable",
                JsonArray().apply {
                  repeat(65) { add(JsonParser.parseString("""{"at":{"label":"x"},"length":0}""")) }
                },
            )
      }
    }
    assertThrows<IllegalArgumentException> {
      parse {
        row()
            .add(
                "accessPolicies",
                JsonArray().apply {
                  repeat(65) {
                    add(JsonParser.parseString("""{"from":{"label":"x"},"until":{"label":"y"}}"""))
                  }
                },
            )
      }
    }
    assertThrows<IllegalArgumentException> {
      parse {
        policy().getAsJsonArray("readable")[0].asJsonObject.addProperty("length", 1048576)
      }
    }
  }
}
