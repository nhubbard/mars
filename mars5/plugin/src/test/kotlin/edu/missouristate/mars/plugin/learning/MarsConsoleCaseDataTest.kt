package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.CharacterCodingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsConsoleCaseDataTest {
    private val valid = """{"formatVersion":1,"cases":[{"name":"input 0","input":"0\n","stdout":"1\n","instructionLimit":20000}]}"""
    private fun changed(change: JsonObject.() -> Unit) = JsonParser.parseString(valid).asJsonObject.apply(change).toString().toByteArray()
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun reject(change: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(changed(change)) } }

    @Test
    fun `book profiles retain exact inputs output limits and immutable case order`() {
        for (variant in listOf("plus-one", "wrapped-byte")) {
            val cases = MarsConsoleCaseData.parse(javaClass.getResourceAsStream("/learning/byte-$variant.cases.json")!!.readBytes())
            assertEquals(listOf("0\n", "1\n", "127\n", "128\n", "150\n", "254\n", "255\n"), cases.map { it.input })
            assertTrue(cases.last().expectedOutput.endsWith(if (variant == "plus-one") "256\n" else "0\n"))
            assertTrue(cases.all { it.instructionLimit == 20_000L })
            assertThrows<UnsupportedOperationException> { (cases as MutableList).clear() }
        }
        val empty = MarsConsoleCaseData.parse(changed { row().addProperty("input", ""); row().addProperty("stdout", "") }).single()
        assertEquals("", empty.input); assertEquals("", empty.expectedOutput)
    }

    @Test
    fun `strict syntax fields versions and text kinds cannot acquire executable behavior`() {
        for (raw in listOf("", "[]", "true", "null", "$valid {}", valid.replace("20000", "NaN"),
            valid.replace("\"input 0\"", "\"a\",\"name\":\"b\""))) {
            assertThrows<Exception> { MarsConsoleCaseData.parse(raw.toByteArray()) }
        }
        for (version in listOf("true", "\"1\"", "1.0", "7")) reject { add("formatVersion", JsonParser.parseString(version)) }
        reject { remove("formatVersion") }
        reject { addProperty("action", "RunWhatever") }
        reject { add("cases", JsonObject()) }
        reject { add("cases", JsonArray()) }
        reject { getAsJsonArray("cases").set(0, JsonParser.parseString("null")) }
        reject { row().remove("stdout") }
        reject { row().addProperty("class", "Anything") }
        for (field in listOf("name", "input", "stdout")) reject { row().add(field, JsonArray()) }
        for (field in listOf("name", "input", "stdout")) reject { row().addProperty(field, 42) }
        assertThrows<CharacterCodingException> { MarsConsoleCaseData.parse(byteArrayOf(0xff.toByte())) }
    }

    @Test
    fun `names are unique printable Unicode rather than UI markup or terminal controls`() {
        for (name in listOf("", " ", "line\nname", "\u001b[31mred", "x".repeat(129), "\u00a0", "a\u200b", "\ue000")) {
            reject { row().addProperty("name", name) }
        }
        reject { getAsJsonArray("cases").add(row().deepCopy()) }
        val unicode = "🙂".repeat(128)
        assertEquals(unicode, MarsConsoleCaseData.parse(changed { row().addProperty("name", unicode) }).single().name)
        assertEquals("<html>literal & text", MarsConsoleCaseData.parse(changed { row().addProperty("name", "<html>literal & text") }).single().name)
    }

    @Test
    fun `version four retains larger published allowances without changing legacy or total limits`() {
        for (version in 1..3) reject { addProperty("formatVersion", version); row().addProperty("instructionLimit", 1_000_001) }
        for (limit in listOf(1L, 1_000_000L, 1_000_001L, 3_000_000L)) {
            val parsed = MarsConsoleCaseData.parse(changed { addProperty("formatVersion", 4); row().addProperty("instructionLimit", limit) })
            assertEquals(limit, parsed.single().instructionLimit)
        }
        for (limit in listOf(0L, 3_000_001L)) reject { addProperty("formatVersion", 4); row().addProperty("instructionLimit", limit) }
        fun profile(last: Int) = changed {
            addProperty("formatVersion", 4)
            val template = row().deepCopy()
            add("cases", JsonArray().apply {
                for ((index, limit) in listOf(3_000_000, 3_000_000, 3_000_000, last).withIndex()) {
                    add(template.deepCopy().apply { addProperty("name", "input $index"); addProperty("instructionLimit", limit) })
                }
            })
        }
        assertEquals(10_000_000L, MarsConsoleCaseData.parse(profile(1_000_000)).sumOf { it.instructionLimit })
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(profile(1_000_001)) }
    }

    @Test
    fun `UTF8 text and total work are bounded independently of claimed numeric types`() {
        for (bad in listOf("0", "-1", "true", "null", "[]", "\"20000\"", "0.5", "1e3", "1000001", "10000000")) {
            reject { row().add("instructionLimit", JsonParser.parseString(bad)) }
        }
        fun rows(count: Int, limit: Int): ByteArray = changed {
            val value = row().deepCopy()
            add("cases", JsonArray().apply { repeat(count) { i -> add(value.deepCopy().apply {
                addProperty("name", "input $i"); addProperty("instructionLimit", limit)
            }) } })
        }
        assertEquals(256, MarsConsoleCaseData.parse(rows(256, 20_000)).size)
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(rows(257, 20_000)) }
        assertEquals(10, MarsConsoleCaseData.parse(rows(10, 1_000_000)).size)
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(rows(11, 1_000_000)) }
        for (field in listOf("input", "stdout")) {
            MarsConsoleCaseData.parse(changed { row().addProperty(field, "a".repeat(65536)) })
            for (value in listOf("a".repeat(65537), "€".repeat(21846))) reject { row().addProperty(field, value) }
        }
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(valid.replace("1\\n", "\\ud800").toByteArray()) }
        val controls = "\u0000\t\n\r\u000c🙂"
        assertEquals(controls, MarsConsoleCaseData.parse(changed { row().addProperty("stdout", controls) }).single().expectedOutput)
        assertEquals(1, MarsConsoleCaseData.parse(valid.padEnd(MarsConsoleCaseData.MAX_BYTES, ' ').toByteArray()).size)
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(ByteArray(MarsConsoleCaseData.MAX_BYTES + 1)) }
    }
}
