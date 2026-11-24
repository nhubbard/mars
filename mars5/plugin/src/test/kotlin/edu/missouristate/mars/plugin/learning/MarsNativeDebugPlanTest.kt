package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsNativeDebugPlanTest {
  private val content =
      """{"formatVersion":3,"introduction":"Inspect a byte.","conclusion":"Explain the extension.","steps":[{"kind":"debug","id":"trace-150","explanation":"Debug with 150.","integerInputs":[150],"stops":[{"id":"after-extension","explanation":"Pause before reporting.","anchor":{"label":"report","mnemonic":"la","occurrence":0},"registers":{"${'$'}t0":"0x0000000000000096","${'$'}t1":"0xFFFFFFFFFFFFFF96"},"success":"The high bits are ones.","hints":[{"id":"width","text":"Inspect all sixteen digits."}]}],"success":"Both interpretations use the same low byte."}]}"""

  private fun JsonObject.debug() = getAsJsonArray("steps")[0].asJsonObject

  private fun JsonObject.stop() = debug().getAsJsonArray("stops")[0].asJsonObject

  private fun changed(change: JsonObject.() -> Unit) =
      JsonParser.parseString(content).asJsonObject.apply(change).toString().toByteArray()

  private fun reject(change: JsonObject.() -> Unit) {
    assertThrows<IllegalArgumentException> { MarsNativeLessonPlan.parse(changed(change)) }
  }

  @Test
  fun `format three retains ordered immutable full-width Debug observations`() {
    val debug =
        MarsNativeLessonPlan.parse(content.toByteArray()).steps.single() as MarsNativeDebugStep
    assertEquals(listOf(150), debug.integerInputs)
    assertEquals(MarsLessonAnchor("report", "la", 0), debug.anchor)
    assertEquals(mapOf("\$t0" to 150L, "\$t1" to -106L), debug.stops.single().registers)
    assertEquals("The high bits are ones.", debug.stops.single().success)
    assertEquals(
        listOf(MarsLessonHint("width", "Inspect all sixteen digits.")),
        debug.stops.single().hints,
    )
    assertTrue(debug.hints.isEmpty())
    assertThrows<UnsupportedOperationException> { (debug.stops as MutableList).clear() }
    assertThrows<UnsupportedOperationException> {
      (debug.stops.single().registers as MutableMap).clear()
    }
    val numeric =
        MarsNativeLessonPlan.parse(
                changed {
                  stop()
                      .add(
                          "registers",
                          JsonObject().apply {
                            addProperty("\$8", "0x0000000000000096")
                            addProperty("\$9", "0xffffffffffffff96")
                          },
                      )
                },
            )
            .steps
            .single() as MarsNativeDebugStep
    assertEquals(debug.stops.single().registers, numeric.stops.single().registers)
  }

  @Test
  fun `Debug is explicitly versioned and cannot contain actions scripts or arbitrary expressions`() {
    reject { addProperty("formatVersion", 2) }
    reject { debug().addProperty("action", "StepWhatever") }
    reject { debug().addProperty("anchor", "anything") }
    reject { stop().addProperty("script", "anything") }
    reject { stop().remove("anchor") }
    reject { stop().remove("success") }
    reject { debug().remove("integerInputs") }
    reject { debug().addProperty("success", " ") }
    reject { debug().getAsJsonArray("stops").add(stop().deepCopy()) }
  }

  @Test
  fun `register contracts reject missing bits unsupported banks and duplicate aliases`() {
    for (bad in
        listOf(
            "\"0x96\"",
            "\"0x00000000000000000\"",
            "\"0x000000000000000g\"",
            "\"-106\"",
            "150",
            "null",
            "true",
            "{}",
        )) {
      reject { stop().getAsJsonObject("registers").add("\$t0", JsonParser.parseString(bad)) }
    }
    for (bad in listOf("", "pc", "\$f0", "\$w0", "\$32", "evaluate me")) {
      reject {
        stop().add("registers", JsonObject().apply { addProperty(bad, "0x0000000000000000") })
      }
    }
    reject { stop().getAsJsonObject("registers").addProperty("\$8", "0x0000000000000096") }
    reject { stop().add("registers", JsonObject()) }
    reject { stop().add("registers", JsonArray()) }
    reject {
      stop()
          .add(
              "registers",
              JsonObject().apply { repeat(33) { addProperty("\$$it", "0x0000000000000000") } },
          )
    }
    val all =
        MarsNativeLessonPlan.parse(
                changed {
                  stop()
                      .add(
                          "registers",
                          JsonObject().apply {
                            repeat(32) { addProperty("\$$it", "0x0000000000000000") }
                          },
                      )
                },
            )
            .steps
            .single() as MarsNativeDebugStep
    assertEquals(32, all.stops.single().registers.size)
  }

  @Test
  fun `stop sequences have bounded unique identities and optional independent hints`() {
    reject { debug().add("stops", JsonArray()) }
    reject { debug().add("stops", JsonObject()) }
    reject {
      val stop = stop().deepCopy()
      debug()
          .add(
              "stops",
              JsonArray().apply {
                repeat(33) { add(stop.deepCopy().apply { addProperty("id", "s$it") }) }
              },
          )
    }
    val maximum =
        MarsNativeLessonPlan.parse(
                changed {
                  val stop = stop().deepCopy().apply { remove("hints") }
                  debug()
                      .add(
                          "stops",
                          JsonArray().apply {
                            repeat(32) { add(stop.deepCopy().apply { addProperty("id", "s$it") }) }
                          },
                      )
                  debug()
                      .add(
                          "hints",
                          JsonArray().apply {
                            add(JsonParser.parseString("""{"id":"start","text":"Start Debug."}"""))
                          },
                      )
                },
            )
            .steps
            .single() as MarsNativeDebugStep
    assertEquals(32, maximum.stops.size)
    assertTrue(maximum.stops.all { it.hints.isEmpty() })
    assertEquals(listOf(MarsLessonHint("start", "Start Debug.")), maximum.hints)
  }
}
