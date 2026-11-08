package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactory
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.psi.TokenType
import com.intellij.lang.LanguageParserDefinitions
import org.junit.jupiter.api.assertThrows
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import edu.missouristate.mars.mips.instructions.InstructionSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@TestApplication
class MipsParserPlatformTest {
    private val project = projectFixture()

    @Test
    fun `registered native Kotlin parser accepts every canonical instruction example`() = timeoutRunBlocking<Unit> {
        val instructions = InstructionSet().also { it.populate() }
        assertTrue(instructions.instructionList.size > 155, "The basic and pseudo-instruction catalogs must both be populated")
        readAction {
            for (instruction in instructions.instructionList) {
                assertParses(instruction.exampleFormat + "\n")
            }
        }
    }

    @Test
    fun `registered native parser handles macros data directives repeats and equivalences`() = timeoutRunBlocking<Unit> {
        readAction {
            assertParses("""
                .eqv SIZE 8
                .eqv PRINT li ${'$'}v0, 1
                .macro print (%value)
                  li ${'$'}v0, 1
                  add ${'$'}a0, ${'$'}zero, %value
                  syscall
                .end_macro
                .data
                values: .word 1, 2:3, -4
                .double 1.2e-5, +Infinity, NaN
                .asciiz "Hello # world", "escaped\\\"quote"
                .byte 'a', '\\n', '\\123'
                .text
                main: print(10)
                lw ${'$'}t0, values+4(${'$'}sp)
            """.trimIndent() + "\n")
        }
    }

    @Test
    fun `native file type lexer conversion and highlighting obey platform contracts`() = timeoutRunBlocking<Unit> {
        readAction {
            assertSame(MipsFileType, FileTypeManager.getInstance().getFileTypeByExtension("asm"))
            assertSame(MipsFileType, FileTypeManager.getInstance().getFileTypeByExtension("s"))
            assertEquals("MARS MIPS", MipsFileType.name)
            assertEquals("MARS MIPS assembly source", MipsFileType.description)
            assertEquals("asm", MipsFileType.defaultExtension)
            assertNotNull(MipsFileType.icon)
            val definition = LanguageParserDefinitions.INSTANCE.forLanguage(MipsLanguage)
            assertInstanceOf(MipsParserDefinition::class.java, definition)
            assertSame(MipsTypes.file, definition.fileNodeType)
            assertTrue(definition.whitespaceTokens.contains(TokenType.WHITE_SPACE))
            assertTrue(definition.commentTokens.contains(MipsTypes.psi("COMMENT")))
            assertTrue(definition.stringLiteralElements.contains(MipsTypes.psi("STRING")))
            assertThrows<UnsupportedOperationException> { definition.createParser(project.get()) }
            assertSame(MipsTypes.converter, MipsElementTypeConverterFactory().getElementTypeConverter())
            val text = ".macro .end_macro .eqv .word \$t0 %x 1 'a' \"x\" #comment\n + - ( ) , : @ \"unterminated\n'open"
            val lexer = definition.createLexer(project.get())
            lexer.start(text)
            assertSame(text, lexer.bufferSequence)
            assertEquals(text.length, lexer.bufferEnd)
            assertEquals(0, lexer.state)
            val highlight = MipsSyntaxHighlighterFactory().getSyntaxHighlighter(null, null)
            assertInstanceOf(MipsSyntaxHighlighter::class.java, highlight)
            assertNotNull(highlight.highlightingLexer)
            for ((_, type) in MipsTypes.syntax) highlight.getTokenHighlights(MipsTypes.converter.convert(type)!!)
            assertTrue(highlight.getTokenHighlights(TokenType.WHITE_SPACE).isEmpty())
            while (lexer.tokenType != null) {
                val saved = lexer.currentPosition
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                assertTrue(end > start)
                lexer.advance()
                lexer.restore(saved)
                assertEquals(start, lexer.tokenStart)
                assertEquals(end, lexer.tokenEnd)
                lexer.advance()
            }
            assertEquals(text.length, lexer.tokenStart)
            assertEquals(text.length, lexer.tokenEnd)
            lexer.advance()
            assertNull(lexer.tokenType)
            assertSame(TokenType.WHITE_SPACE, MipsTypes.converter.convert(SyntaxTokenTypes.WHITE_SPACE))
        }
    }

    @Test
    fun `native syntax entry point builds the same PSI through the platform builder`() = timeoutRunBlocking<Unit> {
        readAction {
            val text = "main: nop\n"
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("entry.asm", MipsFileType, text)
            val syntax = MipsSyntaxDefinition()
            val builder = PsiSyntaxBuilderFactory.getInstance().createBuilder(file.node, syntax.createLexer(), MipsLanguage, text)
            syntax.parse(builder.getSyntaxTreeBuilder())
            assertEquals(text, builder.getTreeBuilt().text)
        }
    }

    @Test
    fun `incomplete and malformed statements preserve later source lines`() = timeoutRunBlocking<Unit> {
        readAction {
            for (broken in listOf("lw \$t0,4(\$sp", "add \$t0,+", "bad @ token", ".asciiz \"open", ".eqv", ".macro foo (%x")) {
                val text = broken + "\nnext: nop\n"
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("broken.asm", MipsFileType, text)
                assertEquals(text, file.text)
                assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isNotEmpty(), broken)
                assertTrue(file.node.text.contains("next: nop"))
            }
        }
    }

    @Test
    fun `all prefixes of representative extensions remain parseable without losing text`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = ".macro m (%x)\nlocal: lw \$t0,label+4(\$sp) #note\n.asciiz \"hello\"\n.end_macro\n.data\n.word 1:4\n.text\nm(5)\n"
            for (end in 0..source.length) {
                val text = source.take(end)
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("prefix.asm", MipsFileType, text)
                assertEquals(text, file.node.text)
            }
        }
    }

    @Test
    fun `existing MARS program corpus parses without syntax errors`() = timeoutRunBlocking<Unit> {
        val names = javaClass.getResourceAsStream("/mars-programs.list")!!.bufferedReader().use { it.readLines() }
        assertTrue(names.isNotEmpty())
        for (name in names) {
            val text = javaClass.getResourceAsStream("/testPrograms/$name")!!.bufferedReader().use { it.readText() }
            readAction { assertParses(text) }
        }
    }

    @Test
    fun `all grammar alternatives include SPIM macros optional commas and continued data`() = timeoutRunBlocking<Unit> {
        val cases = listOf(
            ".macro empty\n.end_macro\n",
            ".macro empty()\n.end_macro\n",
            ".macro spim(\$arg, \$other)\n add \$arg \$arg \$other\n.end_macro\n",
            ".macro plain %a %b\n nop\n.end_macro\n",
            ".eqv expr, (+1 - 2):4\n.eqv text \"x\" 'a' %p .data \$t0\n",
            "first: second: nop\nlast:\n",
            ".word\n.word 1,2,\n.data\n.word 1\n 2 -3 +4\n \"text\" 'a'\n",
            ".macro m(%x,%y)\n lw \$t0,%x(%y)\n.end_macro\nm()\nm(1,2,)\n",
            "lw \$t0, (\$sp)\n.word (1+2)-3\n.word +(-2)\n",
            ".data\n.globl main\n.extern name,4\n.align 2\n.space 8\n.half 0xffff\n.ascii \"x\"\n",
            ".kdata 0x90000000\n.ktext 0x80000180\n.set noat\n.include \"other.asm\"\n",
        )
        readAction { for (text in cases) assertParses(text) }
    }

    private fun assertParses(source: String) {
        val file = PsiFileFactory.getInstance(project.get()).createFileFromText("example.asm", MipsFileType, source)
        assertInstanceOf(MipsFile::class.java, file)
        assertSame(MipsFileType, file.fileType)
        assertEquals(source, file.text)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.isEmpty(), source + "\n" + errors.joinToString { it.errorDescription + " at " + it.textRange })
    }
}
