package edu.missouristate.mars.plugin.language.syntax

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MipsScannerTest {
    private fun kinds(text: String) = MipsScanner.scan(text).map { it.kind }

    @Test
    fun `percent names follow assembler token boundaries and restart within bounded slices`() {
        val name = "%input!@=?.part"
        for (separator in " \t\r\n,:()#\"'+-") {
            val source = "$name${separator}next"
            assertEquals(MipsToken(MipsTokenKind.PARAMETER, 0, name.length), MipsScanner.next(source, 0))
        }
        val source = "%value-7(%name+8)"
        val tokens = MipsScanner.scan(source)
        assertEquals(listOf("%value-7", "(", "%name+8", ")"), tokens.map { source.substring(it.start, it.end) })
        assertEquals(MipsToken(MipsTokenKind.PARAMETER, 0, 6), MipsScanner.next(source, 0, 7))
        assertEquals(MipsToken(MipsTokenKind.PARAMETER, 0, 1), MipsScanner.next("%", 0))
    }

    @Test
    fun `all token kinds preserve source and restart exactly at token boundaries`() {
        val cases = mapOf(
            " " to MipsTokenKind.SPACE, "\t\u000c " to MipsTokenKind.SPACE,
            "\n" to MipsTokenKind.NEWLINE, "\r" to MipsTokenKind.NEWLINE, "\r\n" to MipsTokenKind.NEWLINE,
            "# comment" to MipsTokenKind.COMMENT, "name" to MipsTokenKind.WORD,
            "_local.\$x" to MipsTokenKind.WORD,
            ".text" to MipsTokenKind.DIRECTIVE, "$" + "a0" to MipsTokenKind.REGISTER,
            "%arg" to MipsTokenKind.PARAMETER, "12" to MipsTokenKind.NUMBER, "0" to MipsTokenKind.NUMBER, "012" to MipsTokenKind.NUMBER,
            ".5e-2" to MipsTokenKind.NUMBER, "1.0E+5" to MipsTokenKind.NUMBER,
            "0x1e" to MipsTokenKind.NUMBER, "0XFF" to MipsTokenKind.NUMBER,
            "\"# not a comment\"" to MipsTokenKind.STRING, "'a'" to MipsTokenKind.CHARACTER,
            "\"open" to MipsTokenKind.UNCLOSED_STRING, "'open" to MipsTokenKind.UNCLOSED_CHARACTER,
            "," to MipsTokenKind.COMMA, ":" to MipsTokenKind.COLON,
            "(" to MipsTokenKind.LEFT_PAREN, ")" to MipsTokenKind.RIGHT_PAREN,
            "+" to MipsTokenKind.PLUS, "-" to MipsTokenKind.MINUS, "@" to MipsTokenKind.BAD_CHARACTER,
        )
        for ((text, kind) in cases) {
            val tokens = MipsScanner.scan(text)
            assertEquals(listOf(MipsToken(kind, 0, text.length)), tokens, text)
            assertEquals(tokens.single(), MipsScanner.next(text, 0))
            assertNull(MipsScanner.next(text, text.length))
        }
    }

    @Test
    fun `escaped quotes backslashes character escapes and octal characters stay intact`() {
        val source = "\"a\\\"#b\\\\\"\n'\\n' '\\\'' '\\123'"
        val tokens = MipsScanner.scan(source)
        assertEquals(listOf(MipsTokenKind.STRING, MipsTokenKind.NEWLINE, MipsTokenKind.CHARACTER,
            MipsTokenKind.SPACE, MipsTokenKind.CHARACTER, MipsTokenKind.SPACE, MipsTokenKind.CHARACTER), tokens.map { it.kind })
        assertEquals(source, tokens.joinToString("") { source.substring(it.start, it.end) })
    }

    @Test
    fun `broken strings never consume the next line and exponent signs do not eat address offsets`() {
        for (newline in listOf("\n", "\r", "\r\n")) {
            for (quote in listOf("\"", "'")) {
                for (suffix in listOf("", "\\")) {
                    val source = quote + "x" + suffix + newline + "next"
                    assertEquals(MipsTokenKind.NEWLINE, MipsScanner.scan(source)[1].kind)
                    assertEquals(MipsTokenKind.WORD, MipsScanner.scan(source)[2].kind)
                }
            }
            assertEquals(MipsTokenKind.NEWLINE, MipsScanner.scan("#x" + newline)[1].kind)
            assertEquals(MipsTokenKind.NEWLINE, MipsScanner.scan(" " + newline)[1].kind)
        }
        for (source in listOf("1-2", "label+4", "0x1e-5", "0X1E+5", "1e-x", "name-2")) {
            assertEquals(3, MipsScanner.scan(source).size, source)
        }
        assertEquals(listOf(MipsTokenKind.NUMBER, MipsTokenKind.PLUS), kinds("1e+"))
    }

    @Test
    fun `scanner handles every prefix and arbitrary restart slice without gaps`() {
        val sample = ".macro m (%x)\r\nlabel: lw \$t0,label+4(\$sp) # note\n.asciiz \"x\\\"y\"\n.byte '\\0'\n.end_macro"
        for (length in 0..sample.length) {
            val text = sample.take(length)
            val tokens = MipsScanner.scan(text)
            assertEquals(text, tokens.joinToString("") { text.substring(it.start, it.end) })
            for (token in tokens) {
                assertTrue(token.end > token.start)
                assertEquals(token, MipsScanner.next(text, token.start))
            }
            assertNull(MipsScanner.next(text, text.length))
        }
        assertEquals(MipsToken(MipsTokenKind.WORD, 1, 2), MipsScanner.next("abc", 1, 2))
        assertEquals(MipsTokenKind.COMMENT, MipsScanner.next("#", 0)?.kind)
        assertEquals(MipsTokenKind.UNCLOSED_STRING, MipsScanner.next("\"\\", 0)?.kind)
        assertEquals(MipsTokenKind.UNCLOSED_STRING, MipsScanner.next("\"", 0)?.kind)
        assertEquals(MipsTokenKind.DIRECTIVE, MipsScanner.next(".", 0)?.kind)
        for ((start, end) in listOf(-1 to 2, 2 to 1, 0 to 4)) {
            assertThrows<IllegalArgumentException> { MipsScanner.next("abc", start, end) }
        }
    }
}
