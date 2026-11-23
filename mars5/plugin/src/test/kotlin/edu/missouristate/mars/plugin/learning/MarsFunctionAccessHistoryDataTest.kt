package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsFunctionAccessHistoryDataTest {
    private val content = """{"formatVersion":14,"cases":[{"name":"full QOI allowance","instructionLimit":6000000,"calls":[{"entry":"decode","returnTo":"returned","arguments":{},"expectedRegisters":{"v0":"0x0000000000000000"},"accessHistory":{"regions":[{"at":{"label":"last"},"length":8},{"at":{"label":"first","offset":-4},"length":16}],"maxAccesses":200000}}]}]}"""
    private fun root() = JsonParser.parseString(content).asJsonObject
    private fun JsonObject.row() = getAsJsonArray("cases")[0].asJsonObject
    private fun JsonObject.call() = row().getAsJsonArray("calls")[0].asJsonObject
    private fun JsonObject.history() = call().getAsJsonObject("accessHistory")
    private fun parse(edit: JsonObject.() -> Unit = {}) = MarsFunctionCaseData.parse(root().apply(edit).toString().toByteArray())
    private fun reject(edit: JsonObject.() -> Unit) { assertThrows<IllegalArgumentException> { parse(edit) } }

    @Test
    fun `format fourteen retains full instruction allowance and immutable invocation history regions`() {
        val case = parse().single()
        assertEquals(6_000_000L, case.instructionLimit)
        val history = case.calls.single().accessHistory!!
        assertEquals(200_000, history.maxAccesses)
        assertEquals(listOf("last", "first"), history.regions.map { it.at.label })
        assertEquals(-4, history.regions.last().at.offset)
        assertThrows<UnsupportedOperationException> { (history.regions as MutableList).clear() }
        assertEquals(65_536, parse { history().remove("maxAccesses") }.single().calls.single().accessHistory!!.maxAccesses)
        assertNull(parse { call().remove("accessHistory") }.single().calls.single().accessHistory)
    }

    @Test
    fun `older formats retain their instruction ceilings and reject history even at old budgets`() {
        for (version in 1..13) {
            reject { addProperty("formatVersion", version); row().addProperty("instructionLimit", 1000) }
            reject {
                addProperty("formatVersion", version); call().remove("accessHistory")
                row().addProperty("instructionLimit", if (version <= 10) 1_000_001 else 3_000_001)
            }
            val limit = if (version <= 10) 1_000_000 else 3_000_000
            assertEquals(limit.toLong(), parse {
                addProperty("formatVersion", version); call().remove("accessHistory"); row().addProperty("instructionLimit", limit)
            }.single().instructionLimit)
        }
        reject { row().addProperty("instructionLimit", 6_000_001) }
        reject { row().addProperty("instructionLimit", 0) }
    }

    @Test
    fun `history rejects malformed shapes unbounded spans unknown fields and case scope`() {
        reject { history().remove("regions") }
        reject { history().addProperty("predicate", "accept") }
        reject { row().add("accessHistory", history().deepCopy()) }
        for (raw in listOf("null", "[]", "false", "\"trace\"")) reject { call().add("accessHistory", JsonParser.parseString(raw)) }
        for (raw in listOf("null", "[]", "{}", "1", "\"windows\"")) reject { history().add("regions", JsonParser.parseString(raw)) }
        reject { history().add("regions", JsonArray().apply { repeat(65) { add(root().history().getAsJsonArray("regions")[0]) } }) }
        for (bad in listOf(0, -1, 1_000_001)) reject { history().addProperty("maxAccesses", bad) }
        reject { history().getAsJsonArray("regions")[0].asJsonObject.addProperty("length", 0) }
        reject { history().getAsJsonArray("regions")[0].asJsonObject.addProperty("start", 1) }
        reject { history().getAsJsonArray("regions").forEach { it.asJsonObject.addProperty("length", 1_048_576) } }
    }

    @Test
    fun `profile aggregate and combined observation capacity stay bounded`() {
        val valid = parse {
            val second = row().deepCopy().apply { addProperty("name", "remaining allowance"); addProperty("instructionLimit", 4_000_000) }
            getAsJsonArray("cases").add(second)
        }
        assertEquals(10_000_000L, valid.sumOf { it.instructionLimit })
        reject { getAsJsonArray("cases").add(row().deepCopy().apply { addProperty("name", "too much") }) }
        reject {
            call().add("accessPolicies", JsonArray().apply {
                repeat(4) { add(JsonParser.parseString("""{"from":{"label":"first"},"until":{"label":"last"},"maxAccesses":1000000}""")) }
            })
        }
    }
}
