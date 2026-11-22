package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionAccessCaseDataTest {
    private val profile = """{"formatVersion":4,"cases":[{"name":"Bounded active span","instructionLimit":1000,"initialMemory":[{"label":"values","bytes":"00000000"}],"noStores":[{"label":"guard","length":4}],"accessBounds":[{"label":"values","length":40,"offset":-4,"permittedOffset":0,"permittedLength":0}],"protectCallerStack":true,"calls":[{"entry":"partition","returnTo":"returned","arguments":{},"expectedRegisters":{},"argumentAddresses":{"a0":{"label":"values"}},"memory":[{"label":"values","bytes":"00000000"}]}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.region() = row().getAsJsonArray("accessBounds")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(
        JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray())
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `version four retains immutable access windows caller protection and earlier memory features`() {
        val case = parse().single()
        assertTrue(case.protectCallerStack)
        val range = case.accessBounds.single()
        assertEquals("values", range.label)
        assertEquals(40, range.byteLength)
        assertEquals(-4, range.offset)
        assertEquals(0, range.permittedOffset)
        assertEquals(0, range.permittedByteLength)
        assertEquals("00000000", case.initialMemory.single().bytes)
        assertEquals("guard", case.noStores.single().label)
        assertEquals("values", case.calls.single().argumentAddresses.getValue("a0").label)
        assertThrows<UnsupportedOperationException> { (case.accessBounds as MutableList).clear() }
        assertTrue(parse { row().remove("accessBounds") }.single().accessBounds.isEmpty())
        assertTrue(parse { row().add("accessBounds", JsonArray()) }.single().accessBounds.isEmpty())
        assertFalse(parse { row().remove("protectCallerStack") }.single().protectCallerStack)
        assertFalse(parse { row().addProperty("protectCallerStack", false) }.single().protectCallerStack)
        assertEquals(4, parse { region().addProperty("permittedOffset", -4); region().addProperty("permittedLength", 4) }
            .single().accessBounds.single().permittedByteLength)
    }

    @Test
    fun `older profile versions reject each new field even when it would be empty or false`() {
        for (version in 1..3) {
            fun JsonObject.legacy() {
                addProperty("formatVersion", version)
                row().remove("accessBounds"); row().remove("protectCallerStack")
                if (version < 3) row().remove("noStores")
                if (version < 2) {
                    row().remove("initialMemory")
                    val call = row().getAsJsonArray("calls")[0].asJsonObject
                    call.remove("argumentAddresses"); call.remove("memory")
                    call.getAsJsonObject("expectedRegisters").addProperty("v0", "0x0000000000000000")
                }
            }
            assertEquals(1, parse { legacy() }.size)
            reject { legacy(); row().addProperty("protectCallerStack", false) }
            reject { legacy(); row().add("accessBounds", JsonArray()) }
        }
        assertEquals(1, parse {
            addProperty("formatVersion", 3); row().remove("accessBounds"); row().remove("protectCallerStack")
        }.size)
    }

    @Test
    fun `closed access fields reject coercion scripts missing fields and impossible subwindows`() {
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(1), JsonPrimitive("true"), JsonObject())) {
            reject { row().add("protectCallerStack", bad) }
        }
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive(4), JsonPrimitive("text"))) {
            reject { row().add("accessBounds", bad) }
            reject { row().getAsJsonArray("accessBounds").set(0, bad) }
            if (bad !is JsonPrimitive || !bad.isString) reject { region().add("label", bad) }
        }
        for (field in listOf("label", "length", "permittedOffset", "permittedLength")) reject { region().remove(field) }
        reject { region().addProperty("script", "checks.kts") }
        for (label in listOf("../outside", "", "x".repeat(129))) reject { region().addProperty("label", label) }
        for (field in listOf("length", "permittedLength")) {
            for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(true), JsonPrimitive("4"), JsonPrimitive(1.5),
                JsonPrimitive(-1), JsonPrimitive(1048577), JsonPrimitive(10000000))) reject { region().add(field, bad) }
        }
        reject { region().addProperty("length", 0) }
        for (field in listOf("offset", "permittedOffset")) {
            reject { region().addProperty(field, "0") }
            reject { region().addProperty(field, 1048577) }
            reject { region().addProperty(field, -1048577) }
        }
        reject { region().addProperty("permittedOffset", -5) }
        reject { region().addProperty("permittedOffset", 36); region().addProperty("permittedLength", 1) }
    }

    @Test
    fun `watched access region count and total size remain bounded`() {
        reject { row().add("accessBounds", JsonArray().apply { repeat(65) { add(region().deepCopy()) } }) }
        reject { row().add("accessBounds", JsonArray().apply { repeat(2) { add(region().deepCopy().apply { addProperty("length", 600000) }) } }) }
        assertEquals(64, parse { row().add("accessBounds", JsonArray().apply { repeat(64) { add(region().deepCopy()) } }) }.single().accessBounds.size)
        assertEquals(1048576, parse { region().addProperty("length", 1048576) }.single().accessBounds.single().byteLength)
        assertEquals(0, parse { region().addProperty("permittedOffset", 36) }.single().accessBounds.single().permittedByteLength)
        assertEquals(0, parse { region().remove("offset") }.single().accessBounds.single().offset)
    }
}
