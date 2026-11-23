package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionCallSetupDataTest {
    private val content = """{"formatVersion":15,"cases":[{"name":"changed input","instructionLimit":1000,"calls":[{"entry":"transform","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"setupMemory":[{"label":"input","offset":3,"bytes":"ff0080"}]}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray())
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `format fifteen retains byte exact ordered setup and omission preserves earlier behavior`() {
        val call = parse().single().calls.single()
        assertEquals("input", call.setupMemory.single().label)
        assertEquals(3, call.setupMemory.single().offset)
        assertEquals("ff0080", call.setupMemory.single().bytes)
        assertThrows<UnsupportedOperationException> { (call.setupMemory as MutableList).clear() }
        assertTrue(parse { call().remove("setupMemory") }.single().calls.single().setupMemory.isEmpty())
        assertEquals(6_000_000, parse { row().addProperty("instructionLimit", 6_000_000) }.single().instructionLimit.toInt())
    }

    @Test
    fun `earlier formats reject invocation setup rather than silently ignore it`() {
        for (version in 1..14) reject { addProperty("formatVersion", version) }
        reject { row().addProperty("instructionLimit", 6_000_001) }
        reject { row().add("setupMemory", call().remove("setupMemory")) }
    }

    @Test
    fun `setup rejects malformed bounded data and executable fields`() {
        for (raw in listOf("null", "{}", "false", "[1]", "\"input\"")) reject { call().add("setupMemory", JsonParser.parseString(raw)) }
        for (raw in listOf("", "f", "gg")) reject { call().getAsJsonArray("setupMemory")[0].asJsonObject.addProperty("bytes", raw) }
        reject { call().getAsJsonArray("setupMemory")[0].asJsonObject.addProperty("code", "accept()") }
        reject { call().getAsJsonArray("setupMemory")[0].asJsonObject.addProperty("offset", 1048577) }
        reject { call().getAsJsonArray("setupMemory")[0].asJsonObject.addProperty("label", "bad label") }
        reject { call().add("setupMemory", JsonArray().apply { repeat(65) { add(JsonParser.parseString("""{"label":"input","bytes":"00"}""")) } }) }
    }
}
