package edu.missouristate.mars.plugin.language.syntax

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
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
        assertEquals(MipsToken(MipsTokenKind.BAD_CHARACTER, 0, 1), MipsScanner.next("%", 0))
        assertEquals(MipsToken(MipsTokenKind.PARAMETER, 0, 1), MipsScanner.next("%", 0, debuggerExpression = true))
    }

    @Test
    fun `all token kinds preserve source and restart exactly at token boundaries`() {
        val cases = mapOf(
            " " to MipsTokenKind.SPACE, "\t " to MipsTokenKind.SPACE,
            "\u000c" to MipsTokenKind.BAD_CHARACTER,
            "\n" to MipsTokenKind.NEWLINE, "\r" to MipsTokenKind.NEWLINE, "\r\n" to MipsTokenKind.NEWLINE,
            "# comment" to MipsTokenKind.COMMENT, "name" to MipsTokenKind.WORD,
            "_local.\$x" to MipsTokenKind.WORD,
            ".text" to MipsTokenKind.DIRECTIVE, "$" + "a0" to MipsTokenKind.REGISTER,
            "%arg" to MipsTokenKind.PARAMETER, "12" to MipsTokenKind.NUMBER, "0" to MipsTokenKind.NUMBER, "012" to MipsTokenKind.NUMBER,
            ".5e-2" to MipsTokenKind.NUMBER, "1.0E+5" to MipsTokenKind.NUMBER,
            ".1_count" to MipsTokenKind.DIRECTIVE, ".5e" to MipsTokenKind.DIRECTIVE, ".5f" to MipsTokenKind.NUMBER,
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
    fun `bitwise operators have restartable boundaries only in debugger mode`() {
        for ((operator, expected) in mapOf(
            "|" to MipsTokenKind.BIT_OR, "^" to MipsTokenKind.BIT_XOR, "&" to MipsTokenKind.BIT_AND,
            "~" to MipsTokenKind.BIT_NOT, "<<" to MipsTokenKind.SHIFT,
            ">>" to MipsTokenKind.SHIFT, ">>>" to MipsTokenKind.SHIFT,
        )) {
            val source = "1${operator}2"
            assertEquals(MipsToken(expected, 1, 1 + operator.length),
                MipsScanner.next(source, 1, debuggerExpression = true))
            assertEquals(MipsToken(MipsTokenKind.BAD_CHARACTER, 0, source.length), MipsScanner.next(source, 0))
        }
        assertEquals(MipsToken(MipsTokenKind.SHIFT, 0, 2),
            MipsScanner.next(">>>", 0, 2, debuggerExpression = true))
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
    fun `unfinished character literals leave delimiters and later values available`() {
        val source = "'A, 'B', 7 # still a comment"
        val tokens = MipsScanner.scan(source)
        assertEquals(listOf("'A", ",", " ", "'B'", ",", " ", "7", " ", "# still a comment"),
            tokens.map { source.substring(it.start, it.end) })
        assertEquals(
            listOf(MipsTokenKind.UNCLOSED_CHARACTER, MipsTokenKind.COMMA, MipsTokenKind.SPACE,
                MipsTokenKind.CHARACTER, MipsTokenKind.COMMA, MipsTokenKind.SPACE, MipsTokenKind.NUMBER,
                MipsTokenKind.SPACE, MipsTokenKind.COMMENT),
            tokens.map { it.kind },
        )
        assertEquals(listOf(MipsTokenKind.UNCLOSED_CHARACTER, MipsTokenKind.COMMENT), kinds("'# comment"))
        assertEquals(listOf(MipsTokenKind.UNCLOSED_CHARACTER, MipsTokenKind.SPACE), kinds("' "))
        assertEquals(MipsToken(MipsTokenKind.UNCLOSED_CHARACTER, 0, 1), MipsScanner.next("'#'", 0, 2))
        assertEquals(listOf(MipsTokenKind.UNCLOSED_CHARACTER, MipsTokenKind.COMMA), kinds("'\\,"))
        assertEquals(listOf(MipsTokenKind.UNCLOSED_CHARACTER, MipsTokenKind.COMMA, MipsTokenKind.NUMBER), kinds("'\\,7"))
        assertEquals(listOf(MipsTokenKind.INVALID_CHARACTER, MipsTokenKind.COMMA, MipsTokenKind.NUMBER), kinds("'\\,',7"))
        assertEquals(listOf(MipsTokenKind.UNCLOSED_CHARACTER, MipsTokenKind.COMMENT), kinds("'\\# keep"))
    }

    @Test
    fun `broken strings never consume the next line and incomplete exponents remain editable`() {
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
        for (source in listOf("1-2", "label+4", "1e-x", "name+ 2")) {
            assertEquals(3, MipsScanner.scan(source).count { it.kind != MipsTokenKind.SPACE }, source)
        }
        assertEquals(listOf(MipsTokenKind.BAD_CHARACTER, MipsTokenKind.PLUS), kinds("1e+"))
    }

    @Test
    fun `source candidates preserve MARS token boundaries and lexical errors`() {
        val tokenizer = Tokenizer()
        for (source in listOf(
            ".double .5e-2, 1.0E+5, 0X1.EP2",
            ".word 0x1e-5, 0X1E+5",
            "name-2: nop",
            "bge-7",
            "1.2fake-4",
            ".eqv TOKEN %value-7",
            "bad%name: nop",
            ".eqv bad@name 7",
            "foo/.include \"file.asm\"",
            "4?5, a!e-7, 7*8",
            "@name-7, @name+value",
            ".double 1.5\u000c, NaN\u000b",
            ".double \u000c1.5, \u000bNaN, \u000b1.25\u000c",
            ".word 0X80000000, 0xffffffff, 4294967295, 077, 09, ١٢",
            ".double 0x1p2, 1e2, 2147483648",
            ".word 0xG, 0x, 1oops, 1e, 0b101, 0x123456789",
            ".macro emit (%value!@?, \$argument)",
            ".macro emit (%, %!, %%, %value-7, \$)",
        )) {
            val assembly = tokenizer.tokenizeLine(1, source, ErrorList(), false)
            val tokens = MipsScanner.scan(source).filter { it.kind !in setOf(MipsTokenKind.SPACE, MipsTokenKind.COMMA) }
            assertEquals((0 until assembly.size()).map { assembly[it].startPos - 1 to assembly[it].endPos - 1 },
                tokens.map { it.start to it.end }, source)
            assertEquals((0 until assembly.size()).filter { assembly[it].type == TokenTypes.ERROR },
                tokens.indices.filter { tokens[it].kind == MipsTokenKind.BAD_CHARACTER }, source)
            for (token in tokens) assertEquals(token, MipsScanner.next(source, token.start))
        }
    }

    @Test
    fun `assembly number validity uses the sign only where the assembler attaches it`() {
        val minimum = "0x080000000"
        val unsignedWord = "0xffffffff"
        val cases = listOf(
            "-$minimum" to MipsTokenKind.NUMBER,
            "head -$minimum" to MipsTokenKind.BAD_CHARACTER,
            "head, ( -$minimum" to MipsTokenKind.NUMBER,
            "head\n-$minimum" to MipsTokenKind.NUMBER,
            ".word +$unsignedWord" to MipsTokenKind.BAD_CHARACTER,
            ".word -$unsignedWord" to MipsTokenKind.BAD_CHARACTER,
            "head, +$unsignedWord" to MipsTokenKind.NUMBER,
            "head, -$unsignedWord" to MipsTokenKind.NUMBER,
            ".word - $unsignedWord" to MipsTokenKind.NUMBER,
        )
        for ((source, kind) in cases) {
            val magnitude = MipsScanner.scan(source).last()
            assertEquals(kind, magnitude.kind, source)
            assertEquals(magnitude, MipsScanner.next(source, magnitude.start), source)
            val line = source.substringAfterLast('\n')
            val tokens = Tokenizer().tokenizeLine(1, line, ErrorList(), false)
            assertEquals(kind == MipsTokenKind.BAD_CHARACTER, tokens[tokens.size() - 1].type == TokenTypes.ERROR, source)
        }
    }

    @Test
    fun `assembly recovery leaves debugger token boundaries unchanged`() {
        assertEquals(MipsToken(MipsTokenKind.WORD, 0, 8),
            MipsScanner.next("bad%name", 0, debuggerExpression = true))
        assertEquals(MipsToken(MipsTokenKind.BAD_CHARACTER, 0, 1),
            MipsScanner.next("@name", 0, debuggerExpression = true))
        assertEquals(MipsToken(MipsTokenKind.SPACE, 0, 2),
            MipsScanner.next("\t\u000c7", 0, debuggerExpression = true))
        assertEquals(MipsToken(MipsTokenKind.SPACE, 0, 1),
            MipsScanner.next(" \r\n", 0, debuggerExpression = true))
        assertEquals(MipsToken(MipsTokenKind.NUMBER, 0, 2),
            MipsScanner.next("0x", 0, debuggerExpression = true))
    }

    @Test
    fun `debugger exponent signs stay distinct from arithmetic operators`() {
        for (source in listOf("0x1e-5", "0X1E+5", "name-2")) {
            val first = requireNotNull(MipsScanner.next(source, 0, debuggerExpression = true))
            val sign = requireNotNull(MipsScanner.next(source, first.end, debuggerExpression = true))
            assertEquals(source.length - 2, first.end, source)
            assertEquals(source.length - 1, sign.end, source)
        }
        for (source in listOf("0x1.8p-2", "0X1.8P+2", "1e-2", ".5E+2", "0", "0e-2")) {
            assertEquals(MipsToken(MipsTokenKind.NUMBER, 0, source.length),
                MipsScanner.next(source, 0, debuggerExpression = true), source)
        }
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
