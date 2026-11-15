package edu.missouristate.mars.plugin.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarsSourceToolsTest {
    @Test
    fun `reads named tools from the entry header in order`() {
        val tools = sourceToolHeaders("""
            # Program description

              # mars:tool bitmap width=1920 base=heap
            # mars:tool cache
            # mars:tool bitmap height=1080
            # mars:tool-shaped ordinary comment
            .text
            # mars:tool counter
        """.trimIndent()) { line, diagnostic -> error("$line: $diagnostic") }
        assertEquals(listOf(
            SourceToolHeader(3, "bitmap", mapOf("width" to "1920", "base" to "heap", "height" to "1080")),
            SourceToolHeader(4, "cache", emptyMap()),
        ), tools)
    }

    @Test
    fun `malformed lines invalidate only the affected tools`() {
        val issues = mutableListOf<Pair<Int, String>>()
        val tools = sourceToolHeaders("""
            # mars:tool
            # mars:tool bitmap width=1024
            # mars:tool bitmap width=512
            # mars:tool cache blocks
            # mars:tool keyboardDisplay =fixed
            # mars:tool branchHistory size=
            # mars:tool cache logging=true
            # mars:tool counter connected=false
        """.trimIndent()) { line, diagnostic -> issues += line to diagnostic }
        assertEquals(listOf(1, 3, 4, 5, 6), issues.map { it.first })
        assertEquals(listOf(SourceToolHeader(8, "counter", mapOf("connected" to "false"))), tools)
    }
}
