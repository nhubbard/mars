package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.FunctionDataAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionAddressSetupDataTest {
    private val profile = """{"formatVersion":22,"cases":[{"name":"A relocated reader context","byteOrder":"little","instructionLimit":100000,"calls":[{"entry":"reader","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"setupAddressMemory":[{"at":{"label":"context","offset":8},"value":{"label":"bytes","offset":7}}]}]}]}"""
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray())
    private fun JsonObject.call() = getAsJsonArray("cases")[0].asJsonObject.getAsJsonArray("calls")[0].asJsonObject

    @Test
    fun `version 22 retains immutable relocated setup and inherited native byte order`() {
        val case = parse().single()
        assertEquals("LITTLE_ENDIAN", case.byteOrder.toString())
        val pointers = case.calls.single().setupAddressMemory
        assertEquals(FunctionDataAddress("context", 8), pointers.single().at)
        assertEquals(FunctionDataAddress("bytes", 7), pointers.single().value)
        assertThrows<UnsupportedOperationException> { (pointers as MutableList).clear() }
        for (version in listOf(1, 15, 21)) assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
        for (version in listOf(21, 22)) assertTrue(parse {
            addProperty("formatVersion", version); call().remove("setupAddressMemory")
        }.single().calls.single().setupAddressMemory.isEmpty())
    }

    @Test
    fun `pointer setup validates exact fields addresses lengths and aggregate byte fixtures`() {
        val original = JsonParser.parseString(profile).asJsonObject.call().getAsJsonArray("setupAddressMemory")[0].asJsonObject
        val edits: List<JsonObject.() -> Unit> = listOf(
            { call().getAsJsonArray("setupAddressMemory")[0].asJsonObject.addProperty("unknown", true) },
            { call().getAsJsonArray("setupAddressMemory")[0].asJsonObject.remove("value") },
            { call().getAsJsonArray("setupAddressMemory")[0].asJsonObject.getAsJsonObject("at").addProperty("label", "bad label") },
            { call().getAsJsonArray("setupAddressMemory")[0].asJsonObject.getAsJsonObject("value").addProperty("offset", 1048577) },
            { call().add("setupAddressMemory", JsonArray().apply { repeat(65) { add(original.deepCopy()) } }) },
            { call().add("setupMemory", JsonParser.parseString("""[{"label":"bytes","bytes":"00","repeat":1048576}]""")) },
        )
        for (edit in edits) assertThrows<IllegalArgumentException> { parse(edit) }
        assertEquals(64, parse {
            call().add("setupAddressMemory", JsonArray().apply { repeat(64) { add(original.deepCopy()) } })
        }.single().calls.single().setupAddressMemory.size)
        assertTrue(parse { call().add("setupAddressMemory", JsonArray()) }.single().calls.single().setupAddressMemory.isEmpty())
    }
}
