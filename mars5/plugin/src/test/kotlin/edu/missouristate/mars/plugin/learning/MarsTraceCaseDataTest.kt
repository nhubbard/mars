package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.ConsoleMemoryCheck
import edu.missouristate.mars.cli.grading.ConsoleReadCheck
import edu.missouristate.mars.cli.grading.ConsoleStoreCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsTraceCaseDataTest {
    private val profile = """{"formatVersion":3,"cases":[{"name":"transactions","input":"","stdout":"","instructionLimit":1000,"initialMemory":[{"address":"0x0000000010010001","bytes":"aabbcc"}],"memory":[{"address":"0x0000000010010000","bytes":"11aabbcc"}],"stores":[{"kind":"bytes","address":"0x00000000ffff0000","length":65536,"port":"0x00000000ffff0010","bytes":"aaaabb"},{"kind":"none","address":"0x0000000010010000","length":8}],"reads":[{"address":"0x0000000010010000","length":8,"allowedAddress":"0x0000000010010001","allowedLength":2}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.store() = row().getAsJsonArray("stores")[0].asJsonObject
    private fun JsonObject.read() = row().getAsJsonArray("reads")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsConsoleCaseData.parse(
        JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray()).single()
    private fun reject(edit: JsonObject.() -> Unit) {
        assertThrows<IllegalArgumentException> { parse(edit) }
    }

    @Test
    fun `version four inherits all memory and access obligations at the larger budget`() {
        val larger = parse { addProperty("formatVersion", 4); row().addProperty("instructionLimit", 3_000_000) }
        assertEquals(parse().copy(instructionLimit = 3_000_000), larger)
        for (list in listOf(larger.initialMemory, larger.memory, larger.stores, larger.reads)) {
            assertThrows<UnsupportedOperationException> { (list as MutableList).clear() }
        }
    }

    @Test
    fun `version three retains immutable setup and complete trace contracts without changing older versions`() {
        val case = parse()
        assertEquals(listOf(ConsoleMemoryCheck(0x10010001, "aabbcc")), case.initialMemory)
        assertEquals(listOf(ConsoleStoreCheck(0xffff0000L, 65536, 0xffff0010L, "aaaabb"), ConsoleStoreCheck(0x10010000, 8)), case.stores)
        assertEquals(listOf(ConsoleReadCheck(0x10010000, 8, 0x10010001, 2)), case.reads)
        for (list in listOf(case.initialMemory, case.stores, case.reads)) {
            assertThrows<UnsupportedOperationException> { (list as MutableList).clear() }
        }
        for (version in listOf(1, 2)) {
            reject { addProperty("formatVersion", version) }
            for (field in listOf("initialMemory", "stores", "reads")) {
                reject {
                    addProperty("formatVersion", version)
                    row().remove("memory")
                    for (other in listOf("initialMemory", "stores", "reads")) if (other != field) row().remove(other)
                }
            }
        }
        assertTrue(parse { for (field in listOf("initialMemory", "stores", "reads")) row().remove(field) }.stores.isEmpty())
        assertEquals("", parse { store().addProperty("bytes", "") }.stores.first().bytes)
        assertEquals(0, parse { read().addProperty("allowedLength", 0) }.reads.single().allowedLength)
    }

    @Test
    fun `trace shape and numeric fields do not permit code coercion or undeclared access kinds`() {
        reject { row().addProperty("script", "run this") }
        for (field in listOf("initialMemory", "stores", "reads")) {
            for (value in listOf("null", "{}", "true", "0")) reject { row().add(field, JsonParser.parseString(value)) }
            reject { row().getAsJsonArray(field).set(0, JsonParser.parseString("null")) }
            reject { row().getAsJsonArray(field).set(0, JsonParser.parseString("{}")) }
        }
        for (kind in listOf("unknown", "ByteSequence", "", "execute")) reject { store().addProperty("kind", kind) }
        for (field in listOf("port", "bytes", "length", "address")) reject { store().remove(field) }
        reject { row().getAsJsonArray("stores")[1].asJsonObject.addProperty("bytes", "") }
        reject { read().addProperty("extra", true) }; reject { read().remove("allowedAddress") }
        for (field in listOf("length", "allowedLength")) for (value in listOf("null", "[]", "true", "\"1\"", "1.5", "-1", "10000000")) {
            reject { read().add(field, JsonParser.parseString(value)) }
        }
        reject { store().addProperty("length", 0) }; reject { store().addProperty("length", 1048577) }
        reject { read().addProperty("length", 1048577) }
        reject { read().addProperty("allowedAddress", "0x000000001000ffff") }
        reject { read().addProperty("allowedAddress", "0x0000000010010009") }
        reject { read().addProperty("allowedLength", 9) }
        reject { store().addProperty("port", "0x0000000100000000") }
        reject { store().addProperty("address", "0x7fffffffffffffff"); store().addProperty("length", 2) }
        reject { read().addProperty("address", "0x7fffffffffffffff"); read().addProperty("length", 2) }
        reject { store().add("port", JsonParser.parseString("42")) }
        reject { store().addProperty("bytes", "00 gg") }
    }

    @Test
    fun `per-case setup and trace budgets are bounded with duplicate fields rejected`() {
        val none = JsonParser.parseString("""{"kind":"none","address":"0x0000000010010000","length":8}""").asJsonObject
        fun checks(count: Int) = JsonArray().apply { repeat(count) { add(none.deepCopy()) } }
        assertEquals(64, parse { row().add("stores", checks(64)); row().remove("reads") }.stores.size)
        reject { row().add("stores", checks(65)) }
        reject { row().add("stores", checks(64)) }
        assertEquals(65536, parse { store().addProperty("bytes", "00".repeat(65536)) }.stores.first().bytes.length / 2)
        reject { store().addProperty("bytes", "00".repeat(65537)) }
        reject {
            store().addProperty("bytes", "00".repeat(32769))
            row().getAsJsonArray("stores").set(1, store().deepCopy())
        }
        reject { row().getAsJsonArray("initialMemory").add(row().getAsJsonArray("initialMemory")[0].deepCopy()) }
        val duplicate = profile.replace("\"allowedLength\":2", "\"allowedLength\":2,\"allowedLength\":0")
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(duplicate.toByteArray()) }
    }
}
