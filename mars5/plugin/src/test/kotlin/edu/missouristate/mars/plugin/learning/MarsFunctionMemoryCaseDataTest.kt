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

class MarsFunctionMemoryCaseDataTest {
    private val profile = """{"formatVersion":2,"cases":[{"name":"Memory result","instructionLimit":1000,"initialMemory":[{"label":"source","offset":1,"bytes":"0080ff"}],"calls":[{"entry":"copy","returnTo":"continuation","arguments":{},"argumentAddresses":{"a0":{"label":"source","offset":1},"a1":{"label":"frame"}},"expectedRegisters":{},"memory":[{"label":"frame","offset":-1,"bytes":"110080ff"}]}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun JsonObject.range() = call().getAsJsonArray("memory")[0].asJsonObject
    private fun JsonObject.address() = call().getAsJsonObject("argumentAddresses").getAsJsonObject("a0")
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(
        JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray())
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `version two retains label relative setup pointer arguments and per return memory results`() {
        val case = parse().single()
        assertEquals("0080ff", case.initialMemory.single().bytes)
        assertEquals(1, case.initialMemory.single().offset)
        val call = case.calls.single()
        assertTrue(call.expectedRegisters.isEmpty())
        assertEquals("source", call.argumentAddresses.getValue("a0").label)
        assertEquals(1, call.argumentAddresses.getValue("a0").offset)
        assertEquals(0, call.argumentAddresses.getValue("a1").offset)
        assertEquals(-1, call.memory.single().offset)
        assertThrows<UnsupportedOperationException> { (case.initialMemory as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (call.argumentAddresses as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (call.memory as MutableList).clear() }
        assertEquals(0, parse { range().remove("offset") }.single().calls.single().memory.single().offset)
    }

    @Test
    fun `old profiles reject new fields and version two optional observations default empty`() {
        reject { addProperty("formatVersion", 1) }
        reject { addProperty("formatVersion", "2") }
        val case = parse {
            row().remove("initialMemory"); call().remove("memory"); call().remove("argumentAddresses")
            call().getAsJsonObject("expectedRegisters").addProperty("v0", "0x0000000000000000")
        }.single()
        assertTrue(case.initialMemory.isEmpty()); assertTrue(case.calls.single().memory.isEmpty())
        assertTrue(case.calls.single().argumentAddresses.isEmpty())
        val empty = parse { row().add("initialMemory", JsonArray()); call().add("argumentAddresses", JsonObject()) }.single()
        assertTrue(empty.initialMemory.isEmpty()); assertTrue(empty.calls.single().argumentAddresses.isEmpty())
        reject { call().remove("memory") }
    }

    @Test
    fun `strict memory objects and address maps reject coercion and executable fields`() {
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive(1), JsonPrimitive("text"))) {
            reject { row().add("initialMemory", bad) }; reject { call().add("memory", bad) }
            reject { call().add("argumentAddresses", bad) }
            reject { call().getAsJsonArray("memory").set(0, bad) }
            reject { call().getAsJsonObject("argumentAddresses").add("a0", bad) }
            if (bad !is JsonPrimitive || !bad.isString) {
                reject { range().add("label", bad) }; reject { range().add("bytes", bad) }
                reject { address().add("label", bad) }
            }
        }
        reject { range().remove("label") }; reject { range().remove("bytes") }
        reject { address().remove("label") }; reject { address().addProperty("script", "anything.kts") }
        reject { range().addProperty("predicate", "true") }
        reject { call().add("argumentAddresses", JsonArray()) }
        for (bad in listOf("", "0", "xx")) reject { range().addProperty("bytes", bad) }
        for (bad in listOf("../file", "", "a".repeat(129))) {
            reject { range().addProperty("label", bad) }; reject { address().addProperty("label", bad) }
        }
    }

    @Test
    fun `offsets ranges and register aliases remain bounded without integer coercion`() {
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive("1"), JsonPrimitive(1.5),
            JsonPrimitive(1048577), JsonPrimitive(-1048577), JsonPrimitive(10000000), JsonPrimitive(-10000000))) {
            reject { range().add("offset", bad) }; reject { address().add("offset", bad) }
        }
        assertEquals(1048576, parse { address().addProperty("offset", 1048576) }.single().calls.single().argumentAddresses.getValue("a0").offset)
        assertEquals(-1048576, parse { range().addProperty("offset", -1048576) }.single().calls.single().memory.single().offset)
        reject { call().getAsJsonObject("arguments").addProperty("a0", "0x0000000000000001") }
        reject { call().getAsJsonObject("arguments").addProperty("\$4", "0x0000000000000001") }
        for (name in listOf("sp", "ra", "zero", "pc", "r".repeat(33))) {
            reject {
                val value = address().deepCopy()
                call().add("argumentAddresses", JsonObject().apply { add(name, value) })
            }
        }
        reject { call().getAsJsonObject("argumentAddresses").add("\$4", address().deepCopy()) }
        reject { call().add("argumentAddresses", JsonObject().apply { repeat(33) { add("r$it", JsonObject()) } }) }
        reject { call().add("memory", JsonArray().apply { repeat(65) { add(range().deepCopy()) } }) }
        reject { row().add("initialMemory", JsonArray().apply { repeat(65) { add(range().deepCopy()) } }) }
        assertEquals(64, parse { call().add("memory", JsonArray().apply { repeat(64) { add(range().deepCopy()) } }) }.single().calls.single().memory.size)
    }
}
