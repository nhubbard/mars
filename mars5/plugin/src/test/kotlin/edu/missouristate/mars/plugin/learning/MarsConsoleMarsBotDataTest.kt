package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.ConsoleMarsBotCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsConsoleMarsBotDataTest {
    private val valid = """{"formatVersion":5,"cases":[{"name":"east","input":"","stdout":"","instructionLimit":100000,"marsBot":{"instructionsPerTick":64,"x":16,"y":0,"heading":90,"moving":false,"penDown":false,"trail":[{"x":0,"y":0},{"x":16,"y":0}]}}]}"""
    private fun parse(change: JsonObject.() -> Unit = {}) =
        MarsConsoleCaseData.parse(JsonParser.parseString(valid).asJsonObject.apply(change).toString().toByteArray()).single()
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.bot() = row().getAsJsonObject("marsBot")

    @Test
    fun `version five retains exact device expectations and detached vertex order`() {
        val bot = parse().marsBot!!
        assertEquals(ConsoleMarsBotCheck(64, 16, 0, 90, false, false, listOf(0 to 0, 16 to 0)), bot)
        assertThrows<UnsupportedOperationException> { (bot.trail as MutableList).clear() }
        assertNull(parse { row().remove("marsBot") }.marsBot)
        assertEquals(3_000_000L, parse { row().addProperty("instructionLimit", 3_000_000) }.instructionLimit)
        assertThrows<IllegalArgumentException> { parse { row().addProperty("instructionLimit", 3_000_001) } }
        for (version in 1..4) assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
        val empty = parse { bot().add("trail", JsonArray()) }.marsBot!!
        assertEquals(emptyList<Pair<Int, Int>>(), empty.trail)
        assertEquals(256, parse { bot().add("trail", JsonArray().apply { repeat(256) { add(JsonParser.parseString("""{"x":0,"y":511}""")) } }) }.marsBot!!.trail.size)
        assertEquals(true, parse { bot().addProperty("moving", true); bot().addProperty("penDown", true) }.marsBot!!.moving)
    }

    @Test
    fun `unknown nested fields wrong shapes and nonboolean flags are rejected`() {
        for (raw in listOf("null", "[]", "0", "true", "\"device\"")) {
            assertThrows<IllegalArgumentException> { parse { row().add("marsBot", JsonParser.parseString(raw)) } }
        }
        assertThrows<IllegalArgumentException> { parse { bot().addProperty("script", "anything") } }
        for (field in listOf("instructionsPerTick", "x", "y", "heading", "moving", "penDown", "trail")) {
            assertThrows<IllegalArgumentException> { parse { bot().remove(field) } }
        }
        for (field in listOf("moving", "penDown")) for (raw in listOf("null", "[]", "0", "\"false\"", "{}")) {
            assertThrows<IllegalArgumentException> { parse { bot().add(field, JsonParser.parseString(raw)) } }
        }
        for (raw in listOf("null", "{}", "\"trail\"", "[null]", "[0]", "[{\"x\":0}]", "[{\"x\":0,\"y\":0,\"script\":true}]")) {
            assertThrows<IllegalArgumentException> { parse { bot().add("trail", JsonParser.parseString(raw)) } }
        }
        assertThrows<IllegalArgumentException> { parse { bot().add("trail", JsonArray().apply { repeat(257) { add(JsonParser.parseString("""{"x":0,"y":0}""")) } }) } }
    }

    @Test
    fun `numeric domains reject fractional coerced negative offboard and unbounded device data`() {
        for (field in listOf("instructionsPerTick", "x", "y", "heading")) for (raw in listOf("null", "[]", "true", "\"16\"", "1.0", "1e2", "-1", "10000000")) {
            assertThrows<IllegalArgumentException> { parse { bot().add(field, JsonParser.parseString(raw)) } }
        }
        for ((field, value) in listOf("instructionsPerTick" to 0, "instructionsPerTick" to 1_000_001, "x" to 512, "y" to 512, "heading" to 360)) {
            assertThrows<IllegalArgumentException> { parse { bot().addProperty(field, value) } }
        }
        for (field in listOf("x", "y")) for (raw in listOf("null", "[]", "true", "\"0\"", "-1", "512", "0.5")) {
            assertThrows<IllegalArgumentException> { parse { bot().getAsJsonArray("trail")[0].asJsonObject.add(field, JsonParser.parseString(raw)) } }
        }
        assertThrows<IllegalArgumentException> { parse { bot().getAsJsonArray("trail").remove(1) } }
        for (rate in listOf(1, 1_000_000)) assertEquals(rate, parse {
            bot().addProperty("instructionsPerTick", rate); bot().addProperty("x", 511); bot().addProperty("y", 511); bot().addProperty("heading", 359)
        }.marsBot!!.instructionsPerTick)
    }
}
