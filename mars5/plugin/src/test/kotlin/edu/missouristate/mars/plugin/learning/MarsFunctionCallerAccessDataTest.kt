package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionCallerAccessDataTest {
  private fun profile() =
      JsonParser.parseString(
              """{"formatVersion":18,"cases":[{"name":"RSA caller objects","instructionLimit":40000000,"calls":[{"entry":"rsa_pow128","returnTo":"function_return","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"callerAccess":{"objects":[{"at":{"label":"left"},"length":128},{"at":{"label":"right"},"length":128}],"readable":[{"at":{"label":"left","offset":4},"length":16}],"writable":[{"at":{"label":"right","offset":8},"length":16}],"maxAccesses":2000000}}]}]}""",
          )
          .asJsonObject

  private fun JsonObject.call() =
      getAsJsonArray("cases")[0].asJsonObject.getAsJsonArray("calls")[0].asJsonObject

  private fun JsonObject.caller() = call().getAsJsonObject("callerAccess")

  private fun parse(value: JsonObject) =
      MarsFunctionCaseData.parse(value.toString().toByteArray()).single()

  private fun reject(edit: JsonObject.() -> Unit) {
    assertThrows<IllegalArgumentException> { parse(profile().apply(edit)) }
  }

  @Test
  fun `version eighteen preserves grouped caller windows capacity and default permissions`() {
    val parsed = parse(profile())
    assertEquals(40_000_000L, parsed.instructionLimit)
    val caller = parsed.calls.single().callerAccess!!
    assertEquals(listOf("left", "right"), caller.objects.map { it.at.label })
    assertEquals(listOf(128, 128), caller.objects.map { it.byteLength })
    assertEquals(4, caller.readable.single().at.offset)
    assertEquals(8, caller.writable.single().at.offset)
    assertEquals(2_000_000, caller.maxAccesses)
    val defaults =
        parse(
                profile().apply {
                  for (field in listOf("maxAccesses", "readable", "writable")) caller()
                      .remove(field)
                },
            )
            .calls
            .single()
            .callerAccess!!
    assertEquals(65_536, defaults.maxAccesses)
    assertEquals(emptyList<Any>(), defaults.readable)
    assertEquals(emptyList<Any>(), defaults.writable)
    assertNull(parse(profile().apply { call().remove("callerAccess") }).calls.single().callerAccess)
  }

  @Test
  fun `old versions reject grouped checks explicitly`() {
    for (version in 1..17) reject {
      addProperty("formatVersion", version)
      getAsJsonArray("cases")[0].asJsonObject.addProperty("instructionLimit", 100000)
    }
  }

  @Test
  fun `strict object fields windows sizes and notice capacity fail import`() {
    for (bad in listOf("null", "[]", "1", "\"caller\"", "true")) reject {
      call().add("callerAccess", JsonParser.parseString(bad))
    }
    reject { caller().remove("objects") }
    reject { caller().add("objects", JsonParser.parseString("[]")) }
    reject { caller().addProperty("script", "read anything") }
    for (bad in listOf("null", "\"2\"", "2.0", "2e6", "-1", "0", "2000001")) reject {
      caller().add("maxAccesses", JsonParser.parseString(bad))
    }
    reject { caller().getAsJsonArray("objects")[0].asJsonObject.addProperty("length", 0) }
    reject { caller().getAsJsonArray("objects")[0].asJsonObject.addProperty("execute", true) }
  }
}
