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

class MarsFunctionProtectionCaseDataTest {
    private val profile = """{"formatVersion":3,"cases":[{"name":"Protected input stores","instructionLimit":1000,"initialMemory":[{"label":"record","bytes":"11223344"}],"noStores":[{"label":"record","length":4},{"label":"guard","length":4,"offset":-1}],"calls":[{"entry":"read_record","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000011"},"argumentAddresses":{"a0":{"label":"record"}},"memory":[{"label":"record","bytes":"11223344"}]}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.region() = row().getAsJsonArray("noStores")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(
        JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray())
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `version three retains immutable protected regions alongside memory and data arguments`() {
        val case = parse().single()
        assertEquals(2, case.noStores.size)
        assertEquals("record", case.noStores.first().label)
        assertEquals(4, case.noStores.first().byteLength)
        assertEquals(0, case.noStores.first().offset)
        assertEquals(-1, case.noStores.last().offset)
        assertEquals("11223344", case.initialMemory.single().bytes)
        assertEquals("record", case.calls.single().argumentAddresses.getValue("a0").label)
        assertEquals("11223344", case.calls.single().memory.single().bytes)
        assertThrows<UnsupportedOperationException> { (case.noStores as MutableList).clear() }
        assertTrue(parse { row().remove("noStores") }.single().noStores.isEmpty())
        assertTrue(parse { row().add("noStores", JsonArray()) }.single().noStores.isEmpty())
    }

    @Test
    fun `older versions cannot silently acquire store history checks`() {
        for (version in listOf(1, 2)) reject { addProperty("formatVersion", version) }
        assertEquals(1, parse { addProperty("formatVersion", 2); row().remove("noStores") }.size)
        assertEquals(1, parse {
            addProperty("formatVersion", 1); row().remove("noStores"); row().remove("initialMemory")
            val call = row().getAsJsonArray("calls")[0].asJsonObject
            call.remove("argumentAddresses"); call.remove("memory")
        }.size)
    }

    @Test
    fun `protected range shape rejects executable fields and JSON coercion`() {
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive(4), JsonPrimitive("text"))) {
            reject { row().add("noStores", bad) }
            reject { row().getAsJsonArray("noStores").set(0, bad) }
            if (bad !is JsonPrimitive || !bad.isString) reject { region().add("label", bad) }
        }
        reject { region().remove("label") }; reject { region().remove("length") }
        reject { region().addProperty("script", "check.kts") }
        for (label in listOf("../outside", "", "x".repeat(129))) reject { region().addProperty("label", label) }
        for (length in listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive("4"), JsonPrimitive(1.5),
            JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1048577), JsonPrimitive(10000000))) {
            reject { region().add("length", length) }
        }
        reject { region().addProperty("offset", 1048577) }
        reject { region().addProperty("offset", "0") }
        reject { region().addProperty("offset", -1048577) }
    }

    @Test
    fun `region counts and combined spans remain bounded before any submission is opened`() {
        reject { row().add("noStores", JsonArray().apply { repeat(65) { add(region().deepCopy()) } }) }
        reject { row().getAsJsonArray("noStores").forEach { it.asJsonObject.addProperty("length", 600000) } }
        assertEquals(64, parse { row().add("noStores", JsonArray().apply { repeat(64) { add(region().deepCopy()) } }) }.single().noStores.size)
        assertEquals(1048576, parse {
            val range = region().deepCopy().apply { addProperty("length", 1048576) }
            row().add("noStores", JsonArray().apply { add(range) })
        }.single().noStores.single().byteLength)
    }
}
