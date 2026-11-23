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

class MarsFunctionCaseDataTest {
    @Test
    fun `actual public row-function profile retains all inputs upper patterns and distinct continuations`() {
        val data = javaClass.getResourceAsStream("/learning/row-functions.cases.json")!!.readBytes()
        val cases = MarsFunctionCaseData.parse(data)
        assertEquals(3, cases.size)
        for (case in cases) {
            assertEquals(256, case.calls.size)
            assertEquals(10, case.preservedRegisters.size)
            for ((input, call) in case.calls.withIndex()) {
                assertEquals(input.toLong(), call.arguments.getValue("a0") and 255L)
                assertEquals(((Integer.reverse(input) ushr 24) xor 255).toLong(), call.expectedRegisters.getValue("v0"))
                assertEquals(if (input % 2 == 0) "after_invert" else "after_repeat", call.returnTo)
            }
        }
        assertTrue(cases.last().calls.first().arguments.getValue("a0") < 0)
    }

    private val profile = """{"formatVersion":1,"cases":[{"name":"A callable byte operation","instructionLimit":1000,"calls":[{"entry":"function","returnTo":"continuation","arguments":{"a0":"0x8000000000000016"},"expectedRegisters":{"v0":"0x0000000000000097"}}],"initialRegisters":{"s0":"0x123456789abcdef0"},"preservedRegisters":["s0","sp"],"helpers":[{"from":"helper","until":"helper_return"}],"stackAlignment":16,"callerStackBytes":32}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun changed(edit: JsonObject.() -> Unit) = JsonParser.parseString(profile).asJsonObject.apply(edit).toString().toByteArray()
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(changed(edit))
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `full-width function contracts retain immutable calls setup preservation and helper observations`() {
        val case = parse().single()
        assertEquals(Long.MIN_VALUE + 22, case.calls.single().arguments.getValue("a0"))
        assertEquals(151L, case.calls.single().expectedRegisters.getValue("v0"))
        assertEquals(0x123456789abcdef0L, case.initialRegisters.getValue("s0"))
        assertEquals(listOf("s0", "sp"), case.preservedRegisters)
        assertEquals("helper_return", case.helpers.single().until)
        assertEquals(16, case.stackAlignment); assertEquals(32, case.callerStackBytes)
        assertThrows<UnsupportedOperationException> { (parse() as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (case.calls as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (case.calls.single().arguments as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (case.calls.single().expectedRegisters as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (case.initialRegisters as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (case.preservedRegisters as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (case.helpers as MutableList).clear() }
        assertEquals(-1L, parse { call().getAsJsonObject("arguments").addProperty("a0", "0xFFFFFFFFFFFFFFFF") }
            .single().calls.single().arguments.getValue("a0"))
    }

    @Test
    fun `optional observations default explicitly and empty arrays remain valid`() {
        val optional = listOf("initialRegisters", "preservedRegisters", "helpers", "stackAlignment", "callerStackBytes")
        val minimal = parse { optional.forEach { row().remove(it) } }.single()
        assertTrue(minimal.initialRegisters.isEmpty()); assertTrue(minimal.preservedRegisters.isEmpty()); assertTrue(minimal.helpers.isEmpty())
        assertEquals(16, minimal.stackAlignment); assertEquals(32, minimal.callerStackBytes)
        val empty = parse {
            row().add("helpers", JsonArray()); row().add("preservedRegisters", JsonArray()); row().add("initialRegisters", JsonObject())
            call().add("arguments", JsonObject()); row().addProperty("callerStackBytes", 0)
        }.single()
        assertEquals(0, empty.callerStackBytes); assertTrue(empty.calls.single().arguments.isEmpty())
    }

    @Test
    fun `strict shape encoding and text reject unsupported executable behavior`() {
        for (raw in listOf("", "[]", "null", "true", "{bad", profile + " {}", """{"formatVersion":1,"formatVersion":1,"cases":[]}""")) {
            assertThrows<Exception> { MarsFunctionCaseData.parse(raw.toByteArray()) }
        }
        assertThrows<Exception> { MarsFunctionCaseData.parse(byteArrayOf(0xc3.toByte())) }
        reject { addProperty("formatVersion", 17) }; reject { addProperty("formatVersion", "1") }
        reject { remove("cases") }; reject { addProperty("script", "anything.kts") }
        reject { row().remove("instructionLimit") }; reject { row().addProperty("action", "RunWhatever") }
        reject { call().remove("returnTo") }; reject { call().addProperty("predicate", "true") }
        reject { row().getAsJsonArray("helpers")[0].asJsonObject.addProperty("script", "anything") }
        reject { row().getAsJsonArray("helpers")[0].asJsonObject.remove("until") }
        reject { row().addProperty("name", "x".repeat(129)) }
        for (name in listOf("", " ", "line\nbreak", "zero\u0000", "invisible\u200b", "no\u00a0space")) {
            reject { row().addProperty("name", name) }
        }
        assertThrows<IllegalArgumentException> { MarsFunctionCaseData.parse(profile.replace("A callable byte operation", "bad\\ud800").toByteArray()) }
        assertEquals("Café 😀", parse { row().addProperty("name", "Café 😀") }.single().name)
    }

    @Test
    fun `arrays objects and register values cannot be coerced from other JSON types`() {
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive(1), JsonPrimitive(true), JsonPrimitive("text"))) {
            reject { add("cases", bad) }; reject { row().add("calls", bad) }
            reject { row().add("helpers", bad) }; reject { row().add("preservedRegisters", bad) }
            reject { row().add("initialRegisters", bad) }; reject { call().add("arguments", bad) }
            if (bad !is JsonPrimitive || !bad.isString) reject { row().add("name", bad) }
            reject { getAsJsonArray("cases").set(0, bad) }
            reject { row().getAsJsonArray("calls").set(0, bad) }
            reject { row().getAsJsonArray("helpers").set(0, bad) }
            reject { row().getAsJsonArray("preservedRegisters").set(0, bad) }
            reject { call().getAsJsonObject("expectedRegisters").add("v0", bad) }
        }
        reject { call().add("expectedRegisters", JsonArray()) }
        for (bad in listOf("0x97", "0x00000000000000000", "0x000000000000000g", "0X0000000000000097", "151")) {
            reject { call().getAsJsonObject("expectedRegisters").addProperty("v0", bad) }
        }
        reject { call().add("expectedRegisters", JsonObject().apply { repeat(33) { addProperty("r$it", "0x0000000000000000") } }) }
        reject { call().add("expectedRegisters", JsonObject().apply { addProperty("x".repeat(33), "0x0000000000000000") }) }
    }

    @Test
    fun `integer controls use exact bounded JSON numbers`() {
        for (field in listOf("instructionLimit", "stackAlignment", "callerStackBytes")) {
            for (value in listOf("null", "true", "[]", "{}", "\"16\"", "-1", "0.5", "1e2", "10000000")) {
                reject { row().add(field, JsonParser.parseString(value)) }
            }
        }
        for ((field, value) in listOf("instructionLimit" to 0, "instructionLimit" to 1_000_001,
            "stackAlignment" to 0, "stackAlignment" to 3, "stackAlignment" to 8192, "callerStackBytes" to 65537)) {
            reject { row().addProperty(field, value) }
        }
        val boundary = parse { row().addProperty("instructionLimit", 1_000_000); row().addProperty("stackAlignment", 4096); row().addProperty("callerStackBytes", 65536) }.single()
        assertEquals(65536, boundary.callerStackBytes)
    }

    @Test
    fun `version eleven admits the PPM budget without changing older contracts or profile bounds`() {
        for (version in 1..10) {
            reject { addProperty("formatVersion", version); row().addProperty("instructionLimit", 1_000_001) }
        }
        for (limit in listOf(1L, 1_000_000L, 1_000_001L, 3_000_000L)) {
            assertEquals(limit, parse { addProperty("formatVersion", 11); row().addProperty("instructionLimit", limit) }.single().instructionLimit)
        }
        for (limit in listOf(0L, 3_000_001L)) {
            reject { addProperty("formatVersion", 11); row().addProperty("instructionLimit", limit) }
        }
        val tenMillion = parse {
            addProperty("formatVersion", 11)
            val template = row().deepCopy()
            add("cases", JsonArray().apply {
                for ((index, limit) in listOf(3_000_000, 3_000_000, 3_000_000, 1_000_000).withIndex()) {
                    add(template.deepCopy().apply { addProperty("name", "case$index"); addProperty("instructionLimit", limit) })
                }
            })
        }
        assertEquals(10_000_000L, tenMillion.sumOf { it.instructionLimit })
        reject {
            addProperty("formatVersion", 11)
            val template = row().deepCopy()
            add("cases", JsonArray().apply {
                repeat(4) { add(template.deepCopy().apply { addProperty("name", "case$it"); addProperty("instructionLimit", 3_000_000) }) }
            })
        }
    }

    @Test
    fun `core validation rejects alias collisions invalid labels and unsupported register banks`() {
        reject { call().getAsJsonObject("arguments").addProperty("\$4", "0x0000000000000016") }
        reject { row().getAsJsonArray("preservedRegisters").add("\$16") }
        reject { call().add("expectedRegisters", JsonObject()) }
        for (name in listOf("", "$")) {
            reject { call().add("expectedRegisters", JsonObject().apply { addProperty(name, "0x0000000000000000") }) }
            reject { row().add("preservedRegisters", JsonArray().apply { add(name) }) }
        }
        reject { call().addProperty("entry", "../outside") }; reject { call().addProperty("returnTo", "function") }
        reject { row().getAsJsonArray("helpers").add(row().getAsJsonArray("helpers")[0].deepCopy()) }
        for (register in listOf("pc", "f0", "w0", "sp", "ra", "zero", "")) {
            reject { call().add("arguments", JsonObject().apply { addProperty(register, "0x0000000000000000") }) }
        }
        val all = parse {
            call().add("expectedRegisters", JsonObject().apply { repeat(32) { addProperty("\$$it", "0x0000000000000000") } })
        }.single()
        assertEquals(32, all.calls.single().expectedRegisters.size)
    }

    @Test
    fun `profiles bound case counts call counts cumulative budgets and total bytes`() {
        reject { add("cases", JsonArray()) }; reject { row().add("calls", JsonArray()) }
        reject { val row = row().deepCopy(); add("cases", JsonArray().apply { repeat(257) { add(row.deepCopy().apply { addProperty("name", "case$it") }) } }) }
        reject { val call = call().deepCopy(); row().add("calls", JsonArray().apply { repeat(513) { add(call) } }) }
        reject { row().add("preservedRegisters", JsonArray().apply { repeat(33) { add("s0") } }) }
        reject { row().add("helpers", JsonArray().apply { repeat(33) { add(JsonObject()) } }) }
        reject { getAsJsonArray("cases").add(row().deepCopy()) }
        reject {
            val row = row().deepCopy().apply { addProperty("instructionLimit", 1_000_000) }
            add("cases", JsonArray().apply { repeat(11) { add(row.deepCopy().apply { addProperty("name", "case$it") }) } })
        }
        val manyCalls = JsonParser.parseString("""{"entry":"f","returnTo":"r","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"}}""")
        reject {
            val row = row().deepCopy().apply { add("calls", JsonArray().apply { repeat(512) { add(manyCalls) } }) }
            add("cases", JsonArray().apply { repeat(9) { add(row.deepCopy().apply { addProperty("name", "case$it") }) } })
        }
        assertEquals(512, parse { row().add("calls", JsonArray().apply { repeat(512) { add(manyCalls) } }) }.single().calls.size)
        assertEquals(256, parse {
            val row = row().deepCopy(); add("cases", JsonArray().apply { repeat(256) { add(row.deepCopy().apply { addProperty("name", "case$it") }) } })
        }.size)
        val padded = profile.padEnd(MarsFunctionCaseData.MAX_BYTES, ' ').toByteArray()
        assertEquals(1, MarsFunctionCaseData.parse(padded).size)
        assertThrows<IllegalArgumentException> { MarsFunctionCaseData.parse(padded + byteArrayOf(32)) }
    }
}
