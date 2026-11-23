package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionExitPolicyDataTest {
    private val valid = """{"formatVersion":23,"cases":[{"name":"caller","instructionLimit":20000,"calls":[{"entry":"function","returnTo":"done","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"}}],"exit":{"memory":[{"label":"output","bytes":"00"}],"accessPolicies":[{"from":{"label":"output"},"until":{"label":"end"},"checkReads":false}]}}]}"""
    private fun json() = JsonParser.parseString(valid).asJsonObject
    private fun parse(raw: String = valid) = MarsFunctionCaseData.parse(raw.toByteArray()).single()

    @Test
    fun `version twenty three adds bounded continuation policies without changing existing function formats`() {
        val exit = parse().exit!!
        assertEquals("output", exit.memory.single().label)
        assertEquals(false, exit.accessPolicies.single().checkReads)
        assertEquals(emptyList<Any>(), exit.accessPolicies.single().writable)
        for (version in 14..22) {
            assertThrows<IllegalArgumentException> { parse(json().apply { addProperty("formatVersion", version) }.toString()) }
            val legacy = json().apply {
                addProperty("formatVersion", version)
                getAsJsonArray("cases")[0].asJsonObject.getAsJsonObject("exit").remove("accessPolicies")
            }
            assertTrue(parse(legacy.toString()).exit!!.accessPolicies.isEmpty())
        }
        assertTrue(parse(json().apply {
            getAsJsonArray("cases")[0].asJsonObject.getAsJsonObject("exit").remove("accessPolicies")
        }.toString()).exit!!.accessPolicies.isEmpty())
    }

    @Test
    fun `continuation policies reuse strict ranges flags and observation budgets`() {
        for (field in listOf("predicate", "callback")) assertThrows<IllegalArgumentException> {
            parse(json().apply { getAsJsonArray("cases")[0].asJsonObject.getAsJsonObject("exit").addProperty(field, "run code") }.toString())
        }
        for (bad in listOf("null", "true", "{}")) assertThrows<IllegalArgumentException> {
            parse(json().apply { getAsJsonArray("cases")[0].asJsonObject.getAsJsonObject("exit").add("accessPolicies", JsonParser.parseString(bad)) }.toString())
        }
        assertThrows<IllegalArgumentException> { parse(valid.replace("\"checkReads\":false", "\"maxAccesses\":0")) }
    }
}
