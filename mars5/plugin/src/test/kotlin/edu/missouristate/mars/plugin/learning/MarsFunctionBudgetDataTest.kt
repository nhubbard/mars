package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionBudgetDataTest {
    private fun profile(version: Int = 17, limits: List<Long> = listOf(40_000_000)): JsonObject =
        JsonParser.parseString("""{"formatVersion":$version,"cases":[]}""").asJsonObject.apply {
            add("cases", JsonArray().apply {
                limits.forEachIndexed { index, limit ->
                    add(JsonParser.parseString("""{"name":"case $index","instructionLimit":$limit,"calls":[{"entry":"rsa_pow128","returnTo":"function_return","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"}}]}"""))
                }
            })
        }

    private fun parse(value: JsonObject) = MarsFunctionCaseData.parse(value.toString().toByteArray())

    @Test
    fun `version seventeen admits original RSA single and repeated call budgets`() {
        for (limit in listOf(1L, 6_000_001, 20_000_000, 40_000_000)) {
            assertEquals(limit, parse(profile(limits = listOf(limit))).single().instructionLimit)
        }
        assertEquals(40_000_000L, parse(profile(limits = listOf(20_000_000, 20_000_000))).sumOf { it.instructionLimit })
        for (limits in listOf(listOf(0L), listOf(40_000_001L), listOf(40_000_000L, 1L))) {
            assertThrows<IllegalArgumentException> { parse(profile(limits = limits)) }
        }
    }

    @Test
    fun `older versions retain their case and profile budgets`() {
        for (version in 1..16) {
            val maximum = when (version) { in 14..16 -> 6_000_000L; in 11..13 -> 3_000_000L; else -> 1_000_000L }
            assertEquals(maximum, parse(profile(version, listOf(maximum))).single().instructionLimit)
            assertThrows<IllegalArgumentException> { parse(profile(version, listOf(maximum + 1))) }
            assertThrows<IllegalArgumentException> { parse(profile(version, List(11) { 1_000_000L })) }
        }
    }

    @Test
    fun `expanded limits still require exact numeric JSON values`() {
        for (bad in listOf("null", "true", "[]", "{}", "\"20000000\"", "2e7", "20000000.0", "-1", "100000000")) {
            val value = profile()
            value.getAsJsonArray("cases")[0].asJsonObject.add("instructionLimit", JsonParser.parseString(bad))
            assertThrows<IllegalArgumentException> { parse(value) }
        }
        // Expanding instruction budgets must not expand unrelated fixture sizes.
        val value = profile()
        value.getAsJsonArray("cases")[0].asJsonObject.addProperty("callerStackBytes", 20_000_000)
        assertThrows<IllegalArgumentException> { parse(value) }
    }
}
