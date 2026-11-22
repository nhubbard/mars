package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionAddressWindowDataTest {
    private val profile = """{"formatVersion":6,"cases":[{"name":"borrowed slice","instructionLimit":1000,"calls":[{"entry":"parse","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000004"},"expectedAddresses":{"v1":{"label":"input","offset":1}}}],"storeBounds":[{"from":{"label":"begin"},"until":{"label":"end","offset":4},"permittedFrom":{"label":"record"},"permittedLength":8}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun JsonObject.bound() = row().getAsJsonArray("storeBounds")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(
        JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray())

    @Test
    fun `version six retains immutable expected addresses and assembled store endpoints with safe defaults`() {
        val case = parse().single();val addresses = case.calls.single().expectedAddresses;val bound = case.storeBounds.single()
        assertEquals("input", addresses.getValue("v1").label); assertEquals(1, addresses.getValue("v1").offset)
        assertEquals("begin", bound.from.label); assertEquals("end", bound.until.label); assertEquals(4, bound.until.offset)
        assertEquals("record", bound.permittedFrom!!.label); assertEquals(8, bound.permittedByteLength)
        assertThrows<UnsupportedOperationException> { (addresses as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (case.storeBounds as MutableList).clear() }
        val none = parse { bound().remove("permittedFrom"); bound().remove("permittedLength") }.single().storeBounds.single()
        assertEquals(null, none.permittedFrom); assertEquals(0, none.permittedByteLength)
        assertTrue(parse { row().add("storeBounds", JsonArray()); call().add("expectedAddresses", JsonObject()) }.single().storeBounds.isEmpty())
        for (version in 1..5) {
            assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
            assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version); row().remove("storeBounds") } }
        }
        for (version in 1..6) {
            val legacy = parse { addProperty("formatVersion", version); row().remove("storeBounds"); call().remove("expectedAddresses") }.single()
            assertTrue(legacy.storeBounds.isEmpty()); assertTrue(legacy.calls.single().expectedAddresses.isEmpty())
        }
    }

    @Test
    fun `malformed register addresses store bounds and executable extensions reject before execution`() {
        val edits = listOf<JsonObject.() -> Unit>(
            { bound().remove("from") }, { bound().remove("until") },
            { bound().addProperty("script", "check.kts") }, { bound().addProperty("predicate", "true") },
            { bound().remove("permittedFrom") }, { bound().addProperty("permittedLength", -1) },
            { bound().addProperty("permittedLength", 1048577) }, { bound().addProperty("permittedLength", 1.0) },
            { bound().getAsJsonObject("until").addProperty("offset", "4") },
            { bound().getAsJsonObject("from").addProperty("label", "../input") },
            { call().getAsJsonObject("expectedAddresses").add("v0", JsonParser.parseString("{\"label\":\"input\"}")) },
            { call().getAsJsonObject("expectedAddresses").add("3", JsonParser.parseString("{\"label\":\"input\"}")) },
            { call().getAsJsonObject("expectedAddresses").add("no-register", JsonParser.parseString("{\"label\":\"input\"}")) },
            { row().add("storeBounds", JsonArray().apply { repeat(65) { add(JsonParser.parseString(profile).asJsonObject.bound()) } }) },
        )
        edits.forEach { edit -> assertThrows<IllegalArgumentException> { parse(edit) } }
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive("data"))) {
            assertThrows<IllegalArgumentException> { parse { row().add("storeBounds", bad) } }
            assertThrows<IllegalArgumentException> { parse { bound().add("permittedFrom", bad) } }
            assertThrows<IllegalArgumentException> { parse { call().add("expectedAddresses", bad) } }
            assertThrows<IllegalArgumentException> { parse { call().getAsJsonObject("expectedAddresses").add("v1", bad) } }
        }
    }
    @Test
    fun `stored address values remain bounded immutable data and require version six`() {
        val encoded = "[{\"at\":{\"label\":\"pointer\",\"offset\":8},\"value\":{\"label\":\"input\",\"offset\":1}}]"
        val case = parse { call().add("addressMemory", JsonParser.parseString(encoded)) }.single()
        val checks = case.calls.single().addressMemory
        assertEquals("pointer", checks.single().at.label); assertEquals(8, checks.single().at.offset)
        assertEquals("input", checks.single().value.label)
        assertThrows<UnsupportedOperationException> { (checks as MutableList).clear() }
        for (version in 1..5) assertThrows<IllegalArgumentException> {
            parse { addProperty("formatVersion", version); row().remove("storeBounds"); call().remove("expectedAddresses"); call().add("addressMemory", JsonParser.parseString(encoded)) }
        }
        for (bad in listOf("null", "true", "{}", "[null]", "[{}]", "[{\"at\":{\"label\":\"pointer\"}}]",
            "[{\"at\":{\"label\":\"pointer\"},\"value\":{\"label\":\"input\"},\"script\":\"check.kts\"}]")) {
            assertThrows<IllegalArgumentException> { parse { call().add("addressMemory", JsonParser.parseString(bad)) } }
        }
        assertThrows<IllegalArgumentException> {
            parse { call().add("addressMemory", JsonArray().apply { repeat(65) { add(JsonParser.parseString(encoded).asJsonArray[0]) } }) }
        }
    }

    @Test
    fun `forbidden intervals are immutable versioned data rather than executable predicates`() {
        val data = "[{\"from\":\"first_continuation\",\"until\":\"second_continuation\"}]"
        val intervals = parse { row().add("noExecution", JsonParser.parseString(data)) }.single().noExecution
        assertEquals("first_continuation", intervals.single().from)
        assertThrows<UnsupportedOperationException> { (intervals as MutableList).clear() }
        for (version in 1..5) assertThrows<IllegalArgumentException> {
            parse { addProperty("formatVersion", version); row().remove("storeBounds"); call().remove("expectedAddresses"); row().add("noExecution", JsonParser.parseString(data)) }
        }
        for (bad in listOf("null", "{}", "[{}]", "[{\"from\":\"a\",\"until\":\"a\"}]",
            "[{\"from\":\"a\",\"until\":\"b\",\"script\":\"x\"}]"))
            assertThrows<IllegalArgumentException> { parse { row().add("noExecution", JsonParser.parseString(bad)) } }
    }

}
