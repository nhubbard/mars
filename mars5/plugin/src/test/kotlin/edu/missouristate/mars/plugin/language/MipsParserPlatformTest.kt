package edu.missouristate.mars.plugin.language

import com.intellij.lang.ASTNode
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.plugin.MarsSessionService
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsParserPlatformTest {
    private val project = projectFixture()

    @Test
    fun `mixed case macro and equivalence directives assemble and produce native PSI`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .EqV LIMIT 7
            .MaCrO emit (%value)
              addiu ${'$'}t0, ${'$'}zero, %value
            .EnD_MaCrO
            emit(LIMIT)
        """.trimIndent()
        assertAssembles(directory, source)
        readAction {
            val file = assertParses(source)
            assertEquals(".EqV LIMIT 7", file.node.findChildByType(MipsTypes.psi("STATEMENT"))
                ?.findChildByType(MipsTypes.psi("EQUIVALENCE"))?.text)
            val macro = file.children.mapNotNull { it.node.findChildByType(MipsTypes.psi("MACRO_DEFINITION")) }.single()
            assertEquals(".MaCrO", macro.firstChildNode.text)
            assertEquals(".EnD_MaCrO", macro.lastChildNode.text)
        }
    }

    @Test
    fun `macros with boundaries in different files assemble and each file parses`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val open = ".macro emit (%value)"
        val body = "addiu \$t0, \$zero, %value"
        val close = ".end_macro"
        val call = "emit(7)"

        directory.resolve("open.s").writeText(open)
        val closesInEntry = ".include \"open.s\"\n$body\n$close\n$call"
        assertAssembles(directory, closesInEntry)

        val closesInInclude = "$body\n$close"
        directory.resolve("close.s").writeText(closesInInclude)
        val opensInEntry = "$open\n.include \"close.s\"\n$call"
        assertAssembles(directory, opensInEntry)
        readAction {
            for (fragment in listOf(open, closesInEntry, closesInInclude, opensInEntry)) assertParses(fragment)
        }
    }

    @Test
    fun `macro boundary keywords remain replacement text and expand during assembly`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv BEGIN .macro emit (%value)
            .eqv END .end_macro
            .eqv DECLARE .eqv
            DECLARE LIMIT 7
            BEGIN
              addiu ${'$'}t0, ${'$'}zero, %value
            END
            emit(LIMIT)
        """.trimIndent()
        assertAssembles(directory, source)
        readAction { assertParses(source) }
    }

    @Test
    fun `equivalence replacement preserves the original character literal in editor PSI`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val spellings = listOf("'A'" to 65, "'\\n'" to 10, "'\\101'" to 65, "'\\''" to 39, "'\\\\'" to 92)
        for ((literal, value) in spellings) {
            val source = ".eqv VALUE $literal, # preserve the literal\nli \$t0, VALUE\n"
            assertAssembles(directory, source, 0x24080000 or value)
            readAction {
                val statement = assertParses(source).node.findChildByType(MipsTypes.psi("STATEMENT"))!!
                val replacement = statement.findChildByType(MipsTypes.psi("EQUIVALENCE"))!!
                    .findChildByType(MipsTypes.psi("REPLACEMENT"))!!
                assertEquals(literal, replacement.text)
                assertEquals(MipsTypes.psi("CHARACTER"), replacement.firstChildNode.elementType)
            }
        }
    }

    @Test
    fun `macro signatures preserve percent names and assembler accepted punctuation`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val signatures = listOf(
            "%value!" to ")%value!(,",
            "%value-7" to "(%value-7",
            "\$value" to "\$value)",
        )
        for ((name, signature) in signatures) {
            val source = ".macro emit $signature\naddiu \$t0, \$zero, $name\n.end_macro\nemit(7)"
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                val macro = file.children.mapNotNull { it.node.findChildByType(MipsTypes.psi("MACRO_DEFINITION")) }.single()
                val formals = macro.findChildByType(MipsTypes.psi("MACRO_SIGNATURE"))!!
                    .findChildByType(MipsTypes.psi("PARAMETER_LIST"))!!
                val parameters = formals.getChildren(null)
                    .filter { it.elementType == MipsTypes.psi("MACRO_PARAMETER") }
                    .map { it.text }
                assertEquals(listOf(name), parameters)
            }
        }
    }

    private fun assertAssembles(directory: Path, source: String, expectedInstruction: Int = 0x24080007) {
        val entry = directory.resolve("entry.asm").also { it.writeText(source) }
        val context = project.get().service<MarsSessionService>().context
        val program = context.assemble(listOf(entry), null, true, false)
        assertEquals(listOf(expectedInstruction), program.machineList.map { it.binaryStatement })
    }

    @Test
    fun `macro calls retain raw punctuation and tokens accepted by the assembler`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val calls = listOf(
            Triple("emit", "(+,7)", listOf("(", "+", ",", "7", ")")),
            Triple(".emit", "(:,7)", listOf("(", ":", ",", "7", ")")),
            Triple("\$emit", " ),7", listOf(")", ",", "7")),
            Triple("emit", "((,7)", listOf("(", "(", ",", "7", ")")),
            Triple("emit", "(nop,7)", listOf("(", "nop", ",", "7", ")")),
            Triple("emit", "(\"ignored\",7)", listOf("(", "\"ignored\"", ",", "7", ")")),
        )
        for ((name, arguments, tokens) in calls) {
            val call = name + arguments
            val source = ".macro $name (%ignored,%value)\naddiu \$t0, \$zero, %value\n.end_macro\nentry: $call"
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                val candidate = file.children
                    .mapNotNull { it.node.findChildByType(MipsTypes.psi("OPERATION_CANDIDATE")) }.single()
                assertEquals(call, candidate.text)
                val actualTokens = candidate.getChildren(null)
                    .filter { it.elementType == MipsTypes.psi("INVOCATION_TOKEN") }.map { it.text }
                assertEquals(tokens, actualTokens)
            }
        }
    }

    @Test
    fun `macro parameter can supply an operation during expansion`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .macro emit (%ignored,%value)
              addiu ${'$'}t0, ${'$'}zero, %value
            .end_macro
            .macro forward (%operation,%value)
              %operation +,%value
            .end_macro
            forward(emit,7)
        """.trimIndent()
        assertAssembles(directory, source)
        readAction { assertParses(source) }
    }

    @Test
    fun `expandable operand fragments preserve load syntax supplied by equivalences and macros`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val cases = listOf(
            ".eqv OPEN (\nlw \$t0,0 OPEN \$sp)" to "\$t0,0 OPEN \$sp)",
            ".eqv CLOSE )\nlw \$t0,0(\$sp CLOSE" to "\$t0,0(\$sp CLOSE",
            ".macro read (%close)\nlw \$t0,0(\$sp %close\n.end_macro\nread())" to "\$t0,0(\$sp %close",
            ".macro read (\$close)\nlw \$t0,0(\$sp \$close\n.end_macro\nread())" to "\$t0,0(\$sp \$close",
        )
        for ((source, operands) in cases) {
            assertAssembles(directory, source, expectedInstruction = 0x8fa80000.toInt())
            readAction {
                val fragments = descendants(assertParses(source).node, "OPERAND_FRAGMENT")
                assertEquals(listOf(operands), fragments.map { it.text.trimStart() }.toList())
            }
        }
        readAction {
            val ordinary = assertParses("lw \$t0,label+4(\$sp)\n")
            assertEquals(1, descendants(ordinary.node, "ADDRESS_EXPR").count())
            assertTrue(descendants(ordinary.node, "OPERAND_FRAGMENT").none())
        }
    }

    private fun descendants(node: ASTNode, type: String): SyntaxTraverser<ASTNode> {
        val elementType = MipsTypes.psi(type)
        return SyntaxTraverser.astTraverser(node).filterTypes { it == elementType }
    }

    @Test
    fun `include prepass replaces labels equivalences and unfinished instructions`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        directory.resolve("included.s").writeText("addiu \$t0,\$zero,7")
        val lines = listOf(
            ": .include \"included.s\"",
            "before: .include \"included.s\" +",
            ".eqv NEVER 42 .include \"included.s\"",
            "lw \$t0,0(\$sp .include \"included.s\"",
            ".include 1 .InClUdE ,, \"included.s\"",
        )
        for (line in lines) {
            assertAssembles(directory, line)
            readAction {
                val file = assertParses(line)
                val include = descendants(file.node, "INCLUDE_LINE").single()
                assertEquals(line, include.text)
                for (type in listOf("LABEL", "EQUIVALENCE", "INSTRUCTION")) {
                    assertTrue(descendants(file.node, type).none(), line)
                }
            }
        }
    }

    @Test
    fun `an include can replace a macro closing line before macro assembly`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        directory.resolve("body.s").writeText("addiu \$t0,\$zero,7")
        val include = ".end_macro .include \"body.s\""
        val source = ".macro emit\n$include\n.end_macro\nemit"
        assertAssembles(directory, source)
        readAction {
            val file = assertParses(source)
            val macro = descendants(file.node, "MACRO_DEFINITION").single()
            assertEquals(include, descendants(macro, "INCLUDE_LINE").single().text)
            assertEquals(".end_macro", macro.lastChildNode.text)
            assertTrue(descendants(assertParses(".include 1").node, "INCLUDE_LINE").none())
        }
    }

    @Test
    fun `registered native Kotlin parser accepts every canonical instruction example`() = timeoutRunBlocking<Unit> {
        val instructions = InstructionSet().also { it.populate() }
        assertTrue(instructions.instructionList.size > 155, "The basic and pseudo-instruction catalogs must both be populated")
        readAction {
            for (instruction in instructions.instructionList) {
                val source = instruction.exampleFormat
                val statement = assertParses(source + "\n").node.findChildByType(MipsTypes.psi("STATEMENT"))
                assertNotNull(statement?.findChildByType(MipsTypes.psi("INSTRUCTION")), source)
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
            for (broken in listOf("lw \$t0,4(\$sp", "add \$t0,+", "bad @ token", ".asciiz \"open", ".eqv", ".macro foo (1")) {
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
            "first: second: nop\nlast:\nLİ \$v0, 1\nB NOP\n.ent ignored, +\n",
            ".word\n.word 1,2,\n.data\n.word 1\n 2 -3 +4\n \"text\" 'a'\n",
            ".macro m(%x,%y)\n lw \$t0,%x(%y)\n.end_macro\nm()\nm(1,2,)\n",
            "lw \$t0, (\$sp)\n.word (1+2)-3\n.word +(-2)\n",
            ".data\n.globl main\n.extern name,4\n.align 2\n.space 8\n.half 0xffff\n.ascii \"x\"\n",
            ".kdata 0x90000000\n.ktext 0x80000180\n.set noat\n.include \"other.asm\"\n",
        )
        readAction { for (text in cases) assertParses(text) }
    }

    private fun assertParses(source: String): MipsFile {
        val file = PsiFileFactory.getInstance(project.get()).createFileFromText("example.asm", MipsFileType, source)
        val mips = assertInstanceOf(MipsFile::class.java, file)
        assertSame(MipsFileType, file.fileType)
        assertEquals(source, file.text)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.isEmpty(), source + "\n" + errors.joinToString { it.errorDescription + " at " + it.textRange })
        return mips
    }
}
