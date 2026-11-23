package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.FunctionMemoryCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsConsolePublicationDataTest {
    private val state = """{"memory":[{"label":"used","bytes":"01000000"}],"addressMemory":[{"at":{"label":"pointer"},"value":{"label":"ring","offset":3}}],"accessPolicies":[{"from":{"label":"used"},"until":{"label":"end","offset":4},"writable":[{"at":{"label":"used"},"length":4}],"checkReads":false}]}"""
    private val valid = """{"label":"operation_ready","initialMemory":[{"label":"command_count","bytes":"01000000"}],"snapshots":[$state],"finalState":$state}"""
    private fun json(raw: String = valid) = JsonParser.parseString(raw).asJsonObject
    private fun parse(raw: String = valid, version: Int = 7) = MarsConsoleCaseData.parse(
        """{"formatVersion":$version,"cases":[{"name":"caller","input":"","stdout":"","instructionLimit":20000,"publication":$raw}]}""".toByteArray()
    ).single()
    private fun reject(change: JsonObject.() -> Unit) {
        assertThrows<IllegalArgumentException> { parse(json().apply(change).toString()) }
    }

    @Test
    fun `version seven uses the existing strict memory relocation and access policy contracts`() {
        val parsed = parse().publication!!
        assertEquals("operation_ready", parsed.label)
        assertEquals(listOf(FunctionMemoryCheck("command_count", "01000000")), parsed.initialMemory)
        assertEquals(1, parsed.snapshots.size)
        assertEquals("used", parsed.snapshots.single().memory.single().label)
        assertEquals(3, parsed.finalState.addressMemory.single().value.offset)
        assertEquals(false, parsed.finalState.accessPolicies.single().checkReads)
        assertEquals(4, parsed.finalState.accessPolicies.single().writable.single().byteLength)
        for (version in 1..6) assertThrows<IllegalArgumentException> { parse(version = version) }
        val empty = parse(json().apply { add("snapshots", JsonArray()) }.toString()).publication!!
        assertTrue(empty.snapshots.isEmpty())
        val maximum = json().apply { add("snapshots", JsonArray().apply { repeat(256) { add(json(state)) } }) }
        assertEquals(256, parse(maximum.toString()).publication!!.snapshots.size)
    }

    @Test
    fun `unknown fields executable predicates malformed states and oversized schedules are rejected`() {
        for (raw in listOf("null", "true", "[]", "7", "\"marker\"")) assertThrows<IllegalArgumentException> { parse(raw) }
        for (field in json().keySet()) reject { remove(field) }
        reject { addProperty("predicate", "execute host code") }
        for (raw in listOf("null", "true", "[]", "7", "\"not a label\"")) reject { add("label", JsonParser.parseString(raw)) }
        reject { add("initialMemory", JsonObject()) }
        reject { add("snapshots", JsonObject()) }
        reject { add("snapshots", JsonArray().apply { repeat(257) { add(json(state)) } }) }
        reject { add("snapshots", JsonArray().apply { add(JsonObject()) }) }
        reject { add("snapshots", JsonArray().apply { add(4) }) }
        for (raw in listOf("null", "true", "[]", "7")) reject { add("finalState", JsonParser.parseString(raw)) }
        for (field in listOf("memory", "addressMemory", "accessPolicies")) {
            reject { getAsJsonObject("finalState").remove(field) }
            reject { getAsJsonObject("finalState").add(field, JsonObject()) }
        }
        reject { getAsJsonObject("finalState").addProperty("callback", "anything") }
        reject { getAsJsonObject("finalState").getAsJsonArray("memory")[0].asJsonObject.addProperty("bytes", "zz") }
        reject { getAsJsonObject("finalState").getAsJsonArray("addressMemory")[0].asJsonObject.addProperty("address", "0x123") }
        reject { getAsJsonObject("finalState").getAsJsonArray("accessPolicies")[0].asJsonObject.addProperty("checkReads", "false") }
        reject {
            getAsJsonArray("initialMemory")[0].asJsonObject.apply { addProperty("bytes", "00"); addProperty("repeat", 1048576) }
        }
        val root = """{"formatVersion":7,"cases":[{"name":"mixed","input":"","stdout":"","instructionLimit":1,"publication":$valid,"maze":{"kind":"renderer","fixture":0}}]}"""
        assertThrows<IllegalArgumentException> { MarsConsoleCaseData.parse(root.toByteArray()) }
    }
}
