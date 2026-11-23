package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionExitDataTest {
    private val content = """{"formatVersion":14,"cases":[{"name":"complete caller","instructionLimit":6000000,"calls":[{"entry":"main","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"}}],"exit":{"code":0,"memory":[{"label":"status","bytes":"00000000"},{"label":"count","offset":8,"bytes":"0800000001000000"}]}}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.exit() = row().getAsJsonObject("exit")
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(
        JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray()).single()
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `format fourteen keeps signed exit codes and immutable relocated post-exit memory`() {
        val exit = parse().exit!!
        assertEquals(0, exit.code)
        assertEquals(listOf("status", "count"), exit.memory.map { it.label })
        assertEquals(8, exit.memory.last().offset)
        assertThrows<UnsupportedOperationException> { (exit.memory as MutableList).clear() }
        for (code in listOf(Int.MIN_VALUE, -3, 0, 7, Int.MAX_VALUE)) {
            assertEquals(code, parse { exit().addProperty("code", code) }.exit!!.code)
        }
        assertEquals(0, parse { exit().remove("code") }.exit!!.code)
        assertTrue(parse { exit().remove("memory") }.exit!!.memory.isEmpty())
        assertNull(parse { row().remove("exit") }.exit)
    }

    @Test
    fun `old formats and malformed exit fields do not silently alter execution`() {
        for (version in 1..13) reject { addProperty("formatVersion", version); row().addProperty("instructionLimit", 1000) }
        reject { exit().addProperty("command", "run") }
        reject { row().getAsJsonArray("calls")[0].asJsonObject.add("exit", exit().deepCopy()) }
        for (raw in listOf("null", "[]", "true", "\"exit\"")) reject { row().add("exit", JsonParser.parseString(raw)) }
        for (raw in listOf("null", "true", "\"7\"", "1.5", "1e2", "2147483648", "-2147483649")) {
            reject { exit().add("code", JsonParser.parseString(raw)) }
        }
        reject { exit().add("memory", JsonArray().apply { repeat(65) { add(JsonParser.parseString("""{"label":"status","bytes":"00"}""")) } }) }
    }
}
