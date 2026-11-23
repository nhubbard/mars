package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionCallAccessPolicyDataTest {
    private val policy = """{"from":{"label":"begin"},"until":{"label":"end","offset":4},"readable":[{"at":{"label":"input","offset":4},"length":4}],"writable":[{"at":{"label":"output","offset":4},"length":4}]}"""
    private val content = """{"formatVersion":9,"cases":[{"name":"call slices","instructionLimit":1000,"calls":[{"entry":"function","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"accessPolicies":[$policy]}]}]}"""
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(
        JsonParser.parseString(content).asJsonObject.apply(edit).toString().toByteArray()).single()

    @Test
    fun `format nine preserves immutable invocation windows alongside complete case policies`() {
        val case = parse { row().add("accessPolicies", JsonParser.parseString("[$policy]")) }
        val calls = case.calls.single().accessPolicies
        assertEquals(case.accessPolicies, calls)
        assertEquals(4, calls.single().readable.single().at.offset)
        assertEquals(4, calls.single().writable.single().byteLength)
        assertThrows<UnsupportedOperationException> { (calls as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (calls.single().readable as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (calls.single().writable as MutableList).clear() }
    }

    @Test
    fun `previous editions reject invocation policies even when empty and omission retains earlier behavior`() {
        for (version in 1..8) {
            assertThrows<IllegalArgumentException> { parse { addProperty("formatVersion", version) } }
            assertThrows<IllegalArgumentException> { parse {
                addProperty("formatVersion", version); call().add("accessPolicies", JsonArray())
            } }
            assertTrue(parse { addProperty("formatVersion", version); call().remove("accessPolicies") }.calls.single().accessPolicies.isEmpty())
        }
        assertTrue(parse { call().remove("accessPolicies") }.calls.single().accessPolicies.isEmpty())
        assertTrue(parse { call().add("accessPolicies", JsonArray()) }.calls.single().accessPolicies.isEmpty())
    }

    @Test
    fun `format nine retains label relative setup address expectations and memory results`() {
        val case = parse {
            call().add("argumentAddresses", JsonParser.parseString("""{"a0":{"label":"input","offset":1}}"""))
            call().add("expectedAddresses", JsonParser.parseString("""{"v1":{"label":"input"}}"""))
            call().add("memory", JsonParser.parseString("""[{"label":"output","bytes":"0102"}]"""))
            call().add("addressMemory", JsonParser.parseString("""[{"at":{"label":"pointer"},"value":{"label":"input","offset":1}}]"""))
        }.calls.single()
        assertEquals(1, case.argumentAddresses.getValue("a0").offset)
        assertEquals("input", case.expectedAddresses.getValue("v1").label)
        assertEquals("0102", case.memory.single().bytes)
        assertEquals(1, case.addressMemory.single().value.offset)
    }

    @Test
    fun `strict bounded data rejects executable additions malformed windows and excessive invocation permissions`() {
        for (value in listOf("null", "true", "{}", "1")) assertThrows<IllegalArgumentException> {
            parse { call().add("accessPolicies", JsonParser.parseString(value)) }
        }
        assertThrows<IllegalArgumentException> { parse { call().addProperty("execute", "System.exit(0)") } }
        assertThrows<IllegalArgumentException> { parse { call().getAsJsonArray("accessPolicies")[0].asJsonObject.addProperty("predicate", "true") } }
        assertThrows<IllegalArgumentException> { parse { call().getAsJsonArray("accessPolicies")[0].asJsonObject.getAsJsonObject("from").addProperty("label", "read(input)") } }
        assertThrows<IllegalArgumentException> { parse {
            call().add("accessPolicies", JsonArray().apply { repeat(65) { add(JsonParser.parseString(policy)) } })
        } }
        val large = JsonParser.parseString(policy).asJsonObject.apply {
            getAsJsonArray("readable")[0].asJsonObject.addProperty("length", 600000)
        }
        assertThrows<IllegalArgumentException> { parse {
            call().add("accessPolicies", JsonArray().apply { add(large); add(large.deepCopy()) })
        } }
        val zero = parse { call().getAsJsonArray("accessPolicies")[0].asJsonObject.apply {
            getAsJsonArray("readable")[0].asJsonObject.addProperty("length", 0)
            remove("writable")
        } }.calls.single().accessPolicies.single()
        assertEquals(0, zero.readable.single().byteLength)
        assertTrue(zero.writable.isEmpty())
    }
}
