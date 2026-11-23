package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParser
import edu.missouristate.mars.cli.grading.ConsoleMazeCheck
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsConsoleMazeCaseDataTest {
    private val search = """{"kind":"search","walls":"${"00".repeat(64)}","start":0,"goal":63,"fixtureIndex":65535}"""
    private val renderer = """{"kind":"renderer","fixture":4}"""
    private fun parse(maze: String, version: Int = 6, limit: Int = 250000) = MarsConsoleCaseData.parse(
        """{"formatVersion":$version,"cases":[{"name":"maze","input":"","stdout":"","instructionLimit":$limit,"maze":$maze}]}""".toByteArray()
    ).single()
    private fun changed(source: String, field: String, raw: String): String =
        JsonParser.parseString(source).asJsonObject.apply { add(field, JsonParser.parseString(raw)) }.toString()

    @Test
    fun `version six decodes immutable search and renderer parameters with existing work limits`() {
        assertEquals(ConsoleMazeCheck.Search("00".repeat(64), 0, 63, 65535), parse(search).maze)
        assertEquals(ConsoleMazeCheck.Renderer(4), parse(renderer).maze)
        assertEquals(3_000_000L, parse(renderer, limit = 3_000_000).instructionLimit)
        for (version in 1..5) assertThrows<IllegalArgumentException> { parse(search, version) }
        assertThrows<IllegalArgumentException> { parse(renderer, limit = 3_000_001) }
        // Version six still permits ordinary cases without a semantic checker.
        assertEquals(null, MarsConsoleCaseData.parse("""{"formatVersion":6,"cases":[{"name":"plain","input":"","stdout":"","instructionLimit":1}]}""".toByteArray()).single().maze)
        assertEquals(ConsoleMazeCheck.Renderer(0), parse(changed(renderer, "fixture", "0")).maze)
        assertEquals(ConsoleMazeCheck.Search("00".repeat(64), 63, 0, 0), parse(
            changed(changed(changed(search, "start", "63"), "goal", "0"), "fixtureIndex", "0")
        ).maze)
    }

    @Test
    fun `untrusted profiles cannot install predicates unknown keys or malformed numeric kinds`() {
        for (source in listOf(search, renderer)) {
            for (bad in listOf("null", "[]", "true", "7", "\"search\"")) assertThrows<IllegalArgumentException> { parse(bad) }
            for (bad in listOf("\"unknown\"", "null", "true", "7")) assertThrows<IllegalArgumentException> { parse(changed(source, "kind", bad)) }
            assertThrows<IllegalArgumentException> { parse(changed(source, "predicate", "\"run arbitrary code\"")) }
            for (field in JsonParser.parseString(source).asJsonObject.keySet()) {
                val missing = JsonParser.parseString(source).asJsonObject.apply { remove(field) }.toString()
                assertThrows<IllegalArgumentException> { parse(missing) }
            }
        }
        for (field in listOf("start", "goal", "fixtureIndex")) {
            for (raw in listOf("-1", "1.0", "1e0", "true", "null", "[]", "\"1\"", "10000000")) {
                assertThrows<IllegalArgumentException> { parse(changed(search, field, raw)) }
            }
        }
        for (raw in listOf("-1", "5", "1.0", "\"1\"", "null")) assertThrows<IllegalArgumentException> { parse(changed(renderer, "fixture", raw)) }
        for (field in listOf("start", "goal")) assertThrows<IllegalArgumentException> { parse(changed(search, field, "64")) }
        assertThrows<IllegalArgumentException> { parse(changed(search, "fixtureIndex", "65536")) }
        for (raw in listOf("null", "5", "\"\"", "\"${"00".repeat(63)}\"", "\"${"00".repeat(63)}02\"")) {
            assertThrows<IllegalArgumentException> { parse(changed(search, "walls", raw)) }
        }
        for (walls in listOf("01" + "00".repeat(63), "00".repeat(63) + "01")) {
            assertThrows<IllegalArgumentException> { parse(changed(search, "walls", "\"$walls\"")) }
        }
    }
}
