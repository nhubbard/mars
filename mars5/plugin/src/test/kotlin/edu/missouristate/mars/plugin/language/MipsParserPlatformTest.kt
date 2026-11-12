package edu.missouristate.mars.plugin.language

import com.intellij.lang.ASTNode
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.TextRange
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
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.hardware.MemoryConfigurations
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem.Kind
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.util.Binary
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MipsParserPlatformTest {
    private val project = projectFixture()

    @Test
    fun `macro definitions retain raw operands until the assembler expands them`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .macro deferred (%value)
                    addiu ${'$'}t0, +
                    addiu ${'$'}t0, :
                    addiu ${'$'}t0, )
                    lw ${'$'}t0, 0(${'$'}sp
                    .word )
                    .include %value
                    ) 6 (
                    deferredLabel: 4 : )
                    42 43
                .end_macro
                .macro emit (%value)
                    .data
                    .word 1
                    42 43
                    .text
                    addiu ${'$'}a0, ${'$'}zero, %value
                .end_macro
                emit(42)
            """.trimIndent() + "\n"
            assertAssembles(directory, source, 0x2404002a)
            val entry = directory.resolve("entry.asm").also { it.writeText(source + "deferred(1)\n") }
            val context = project.get().service<MarsSessionService>().context
            val memory = context.memory
            assertEquals(listOf(1, 42, 43), (0..2).map { memory.getWordNoNotify(memory.dataBaseAddress + it * Int.SIZE_BYTES) })
            assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }

            readAction {
                val file = assertParses(source)
                assertEquals(listOf("\$t0, +", "\$t0, :", "\$t0, )", "\$t0, 0(\$sp", ")", ") 6 (", "4 : )"),
                    descendants(file.node, "OPERAND_FRAGMENT").map { it.text }.toList())
                val deferredBody = descendants(file.node, "MACRO_DEFINITION").first()
                assertEquals(listOf(") 6 (", "4 : )", "42 43"),
                    descendants(deferredBody, "DATA_CONTINUATION").map { it.text }.toList())
                assertEquals(listOf("42", "43"),
                    descendants(deferredBody, "DATA_CONTINUATION").last().let { line ->
                        descendants(line, "OPERAND").map { it.text }.toList()
                    })
                assertEquals("deferredLabel:", descendants(deferredBody, "LABEL").single().text)
                val validBody = descendants(file.node, "MACRO_DEFINITION").last()
                assertEquals(listOf("1", "42", "43", "\$a0", "\$zero", "%value"),
                    descendants(validBody, "OPERAND").map { it.text }.toList())
                assertEquals(listOf("emit(42)"), descendants(file.node, "OPERATION_CANDIDATE").map { it.text }.toList())

                val factory = PsiFileFactory.getInstance(project.get())
                val invalid = factory.createFileFromText("invalid.asm", MipsFileType,
                    "addiu \$t0, :\n.macro bad\n.word 'bad'\n.end_macro\nnext: nop")
                assertEquals(2, PsiTreeUtil.findChildrenOfType(invalid, PsiErrorElement::class.java).size)
                assertEquals("next:", descendants(invalid.node, "LABEL").single().text)
            }
        }

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
    fun `short equivalence directives are no-ops when the assembler has fewer than three tokens`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val noOps = listOf(".eqv", "  .EQV , # ignored", ".eqv UNSET", ".eqv -7", ".eqv ,,, 7", ".eqv \$t0", ".eqv \"unused\"")
            val source = noOps.joinToString("\n", postfix = "\n.eqv VALUE 7\naddiu \$t0, \$zero, VALUE\n.eqv EOF")
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                assertEquals(listOf(".eqv VALUE 7"), descendants(file.node, "EQUIVALENCE").map { it.text }.toList())
                assertEquals(noOps.size + 1, descendants(file.node, "DIRECTIVE_STATEMENT").count())
            }
            for (line in listOf(".eqv UNSET # comment", "label: .eqv", ".eqv - 7", ".eqv - 7 8")) {
                val entry = directory.resolve("invalid.asm").also { it.writeText("$line\nnop") }
                val context = project.get().service<MarsSessionService>().context
                val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
                assertTrue(failure.errors().errorMessages.any { ".eqv directive" in it.message }, line)
                readAction {
                    val file = PsiFileFactory.getInstance(project.get()).createFileFromText("invalid.asm", MipsFileType, "$line\nnop")
                    assertEquals(1, descendants(file.node, "EQUIVALENCE").count(), line)
                    assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isNotEmpty(), line)
                    assertEquals("nop", descendants(file.node, "INSTRUCTION").single().text)
                }
            }
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
            "%!" to "(%!)",
            "%%" to "(%%)",
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

    @Test
    fun `macro signature aliases expand into a name and parameter list`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv NAME emit
            .eqv PARAMETERS FORMALS
            .eqv FORMALS (%unused, %value)
            .macro NAME PARAMETERS
              addiu ${'$'}t0, ${'$'}zero, %value
            .end_macro
            emit (0, 7)
        """.trimIndent()
        assertAssembles(directory, source)
        readAction {
            val file = assertParses(source)
            val signature = descendants(file.node, "MACRO_SIGNATURE").single()
            assertEquals("NAME PARAMETERS", signature.text)
            assertEquals("PARAMETERS", descendants(signature, "MACRO_PARAMETER").single().text)
        }
    }

    private fun assertAssembles(directory: Path, source: String, expectedInstruction: Int = 0x24080007) {
        val entry = directory.resolve("entry.asm").also { it.writeText(source) }
        val context = project.get().service<MarsSessionService>().context
        val program = try {
            context.assemble(listOf(entry), null, true, false)
        } catch (failure: ProcessingException) {
            fail(failure.errors().errorMessages.joinToString("\n") { it.message }, failure)
        }
        assertEquals(listOf(expectedInstruction), program.machineList.map { it.binaryStatement })
    }

    @Test
    fun `macro calls retain raw punctuation and tokens accepted by the assembler`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val calls = listOf(
            Triple("emit", "(+,7)", listOf("(", "+", "7", ")")),
            Triple(".emit", "(:,7)", listOf("(", ":", "7", ")")),
            Triple("\$emit", " ),7", listOf(")", "7")),
            Triple("emit", "((,7)", listOf("(", "(", "7", ")")),
            Triple("emit", "(nop,7)", listOf("(", "nop", "7", ")")),
            Triple("emit", "(\"ignored\",7)", listOf("(", "\"ignored\"", "7", ")")),
            Triple("emit", "(,-2,7)", listOf("(", "-2", "7", ")")),
            Triple("emit", " -7", listOf("-", "7")),
            Triple("emit", ": 7", listOf(":", "7")),
        )
        for ((name, arguments, tokens) in calls) {
            val call = name + arguments
            val source = ".macro $name (%ignored,%value)\naddiu \$t0, \$zero, %value\n.end_macro\nentry: $call"
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                assertEquals(listOf("entry:"), descendants(file.node, "LABEL").map { it.text }.toList())
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
    fun `macro invocation spans agree with the assembler across adjacent raw arguments`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            data class Invocation(val line: String, val arity: Int, val arguments: List<TextRange>)

            val tokenizer = Tokenizer()
            val left = listOf("name", ".42name", "\$sp", "\$name", "nop", "'\\377'", "1e-2", ".42", "NaN", "%raw")
            val right = listOf("-2", "+3", "-0x080000000", "(,7", ") 8", ":7", "'\\+77'", "\"% raw\"")
            val invocations = buildList {
                for (first in left) for (second in right) for (separator in listOf("", " ", ",,")) {
                    val arguments = "1,$first$separator$second"
                    for (line in listOf("capture $arguments", "capture($arguments)")) {
                        val tokens = tokenizer.tokenizeLine(null, 1, line, false)
                        val written = (1 until tokens.size()).map { tokens[it] }
                        if (written.any { it.type == TokenTypes.ERROR }) continue
                        val arity = written.size - if (Macro.hasCallParentheses(tokens)) 2 else 0
                        val ranges = written.map { TextRange(it.startPos - 1, it.endPos - 1) }
                        add(Invocation(line, arity, ranges))
                    }
                }
            }
            assertEquals(450, invocations.size)
            val source = buildString {
                for (arity in invocations.map { it.arity }.distinct()) {
                    val parameters = (1..arity).joinToString(", ") { "%p$it" }
                    appendLine(".macro capture ($parameters)")
                    appendLine("nop")
                    appendLine(".end_macro")
                }
                invocations.forEach { appendLine(it.line) }
            }
            val entry = directory.resolve("macro-token-spans.asm").also { it.writeText(source) }
            val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry))
            assertEquals(List(invocations.size) { 0 }, program.machineList.map { it.binaryStatement })
            readAction {
                val candidates = descendants(assertParses(source).node, "OPERATION_CANDIDATE").toList()
                assertEquals(invocations.size, candidates.size)
                for ((invocation, candidate) in invocations.zip(candidates)) {
                    assertEquals(invocation.line, candidate.text)
                    val ranges = descendants(candidate, "INVOCATION_TOKEN").map {
                        TextRange.from(it.startOffset - candidate.startOffset, it.textLength)
                    }.toList()
                    assertEquals(invocation.arguments, ranges, invocation.line)
                }
            }
        }

    @Test
    fun `only the leading label is consumed before a raw macro call inside another macro`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .macro emit (%ignored, %value)
                    addiu ${'$'}t0, ${'$'}zero, %value
                .end_macro
                .macro outer
                    local: emit: 7
                .end_macro
                outer
            """.trimIndent()
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                val outer = descendants(file.node, "MACRO_DEFINITION").last()
                assertEquals("local:", descendants(outer, "LABEL").single().text)
                assertEquals("emit: 7", descendants(outer, "OPERATION_CANDIDATE").single().text)
                assertEquals(listOf(":", "7"), descendants(outer, "INVOCATION_TOKEN").map { it.text }.toList())
            }
        }

    @Test
    fun `equivalence replacement PSI preserves signed token boundaries before expansion`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv VALUES 1-2+3
            .eqv SINGLE -7
            .data
            .word VALUES
            .word SINGLE
            .text
            nop
        """.trimIndent()
        assertAssembles(directory, source, 0)
        val memory = project.get().service<MarsSessionService>().context.memory
        assertEquals(listOf(1, -2, 3, -7), (0..3).map { memory.getWordNoNotify(memory.dataBaseAddress + it * 4) })
        readAction {
            val file = assertParses(source)
            val replacements = descendants(file.node, "EQUIVALENCE").map { declaration ->
                descendants(declaration, "REPLACEMENT").map { it.text }.toList()
            }.toList()
            assertEquals(listOf(listOf("1", "-2", "+3"), listOf("-", "7")), replacements)
        }
    }

    @Test
    fun `signed limits assemble in macro arguments and equivalence replacements`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv WORDS 1,-0x080000000
            .data
            .word WORDS
            .macro emit (%value)
              .word %value
            .end_macro
            .macro emit (%ignored,%value)
              .word %value
            .end_macro
            emit(-0x00080000000)
            emit -0xffffffff
            .text
            nop
        """.trimIndent()
        assertAssembles(directory, source, 0)
        val memory = project.get().service<MarsSessionService>().context.memory
        assertEquals(listOf(1, Int.MIN_VALUE, Int.MIN_VALUE, -1), (0..3).map {
            memory.getWordNoNotify(memory.dataBaseAddress + it * Int.SIZE_BYTES)
        })
        readAction {
            val file = assertParses(source)
            assertEquals(listOf("1", "-0x080000000"), descendants(file.node, "REPLACEMENT").map { it.text }.toList())
            val arguments = descendants(file.node, "OPERATION_CANDIDATE")
                .filter { it.text.startsWith("emit") }
                .map { call -> descendants(call, "INVOCATION_TOKEN").map { it.text }.toList() }.toList()
            assertEquals(listOf(listOf("(", "-0x00080000000", ")"), listOf("-", "0xffffffff")), arguments)
        }
    }

    @Test
    fun `macro parameters remain distinct from quoted text and longer names`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv DATAARG %ab
            .macro emit (%a, %ab, %text)
              .word DATAARG, %a, %ab
              .asciiz "%text", %text
            .end_macro
            .data
            emit(7, 11, "A")
            .text
            nop
        """.trimIndent()
        assertAssembles(directory, source, 0)
        val memory = project.get().service<MarsSessionService>().context.memory
        val start = memory.dataBaseAddress
        assertEquals(listOf(11, 7, 11), (0..2).map { memory.getWordNoNotify(start + it * 4) })
        assertEquals("%text\u0000A\u0000".map(Char::code), (12..19).map { memory.getByte(start + it) })
        readAction { assertParses(source) }
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
    fun `macro parameters can supply a label without swallowing its instruction`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        for (parameter in listOf("%entry", "\$entry")) {
            val source = """
                .macro emit ($parameter)
                  $parameter: addiu ${'$'}t0, ${'$'}zero, 7
                .end_macro
                emit(generated_entry)
                .globl generated_entry
            """.trimIndent()
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                val macro = descendants(file.node, "MACRO_DEFINITION").single()
                assertEquals("$parameter:", descendants(macro, "LABEL").single().text)
                assertEquals("addiu", descendants(macro, "OPERATION").single().text)
            }
        }
    }

    @Test
    fun `expandable operand fragments preserve load syntax supplied by equivalences and macros`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val cases = listOf(
            ".eqv OPEN (\nlw \$t0,0 OPEN \$sp)" to "\$t0,0 OPEN \$sp)",
            ".eqv CLOSE )\nlw \$t0,0(\$sp CLOSE" to "\$t0,0(\$sp CLOSE",
            ".eqv BASE \$sp)\nlw \$t0,(BASE" to "\$t0,(BASE",
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
    fun `lexical errors retain valid includes without parsing the replaced prefix`() = timeoutRunBlocking<Unit> {
        readAction {
            val factory = PsiFileFactory.getInstance(project.get())
            val cases = mapOf(
                "discarded: @ .include \"part.s\" 0xG" to listOf("@", "0xG"),
                "'abcde.include \"part.s\"" to listOf("'abcde"),
                "'a#xx .include \"part.s\"" to listOf("'a", "#xx "),
                "'a,xxx.include \"part.s\"" to listOf("'a", "xxx"),
                "'a\"xx .include \"part.s\"" to listOf("'a", "\"xx "),
            )
            for ((line, expectedErrors) in cases) {
                val file = factory.createFileFromText("main.s", MipsFileType, "$line\nnext: nop")
                assertEquals(line, descendants(file.node, "INCLUDE_LINE").single().text)
                assertEquals(listOf("next:"), descendants(file.node, "LABEL").map { it.text }.toList())
                val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
                assertEquals(expectedErrors, errors.map { it.text })
                assertTrue(errors.filter { it.firstChild.node.elementType == MipsTypes.psi("BAD_CHARACTER") }
                    .all { it.errorDescription == "Invalid MARS token on include line" })
                assertEquals(".include", descendants(file.node, "INCLUDE").single().text)
                assertEquals("\"part.s\"", descendants(file.node, "STRING").single().text)
            }
            for (line in listOf("'a .include \"hidden.s\"", ".include \"unfinished")) {
                val file = factory.createFileFromText("main.s", MipsFileType, "$line\nnext: nop")
                assertTrue(descendants(file.node, "INCLUDE_LINE").none(), line)
                assertEquals(listOf("next:"), descendants(file.node, "LABEL").map { it.text }.toList())
            }
            val misleadingStrings = "\".include\" \".include\" .include \"part.s\""
            assertEquals(misleadingStrings, descendants(assertParses(misleadingStrings).node, "INCLUDE_LINE").single().text)
        }
    }

    @Test
    fun `unfinished include paths recover without interpreting their prefixes as statements`() = timeoutRunBlocking<Unit> {
        readAction {
            val factory = PsiFileFactory.getInstance(project.get())
            val cases = mapOf(
                "@ .include \"unfinished" to listOf("@"),
                ".macro ghost .include \"unfinished" to emptyList(),
                "draft: .macro ghost .include \"unfinished" to emptyList(),
                "nop: @ .include \"unfinished" to listOf("@"),
                "'a#xx .include \"unfinished" to listOf("'a", "#xx "),
            )
            for ((line, expectedErrors) in cases) {
                val source = "$line\nnext: nop\n"
                val file = factory.createFileFromText("editing.mars", MipsFileType, source)
                assertEquals(source, file.text)
                val include = descendants(file.node, "INCLUDE_LINE").single()
                assertEquals(line, include.text)
                assertEquals("\"unfinished", descendants(include, "UNCLOSED_STRING").single().text)
                assertEquals(listOf("next:"), descendants(file.node, "LABEL").map { it.text }.toList())
                assertTrue(descendants(file.node, "MACRO_DEFINITION").none())
                assertEquals("nop", descendants(file.node, "INSTRUCTION").single().text)
                assertEquals(expectedErrors, PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.text })
            }
            val invocation = assertParses("call .include \"unfinished")
            assertTrue(descendants(invocation.node, "INCLUDE_LINE").none())
            val ordinary = assertParses("draft: .include \"unfinished")
            assertEquals("draft:", descendants(ordinary.node, "LABEL").single().text)
            assertTrue(descendants(ordinary.node, "INCLUDE_LINE").none())
            val nested = assertParses(".macro outer\ninside: .end_macro .include \"unfinished\nkept: nop\n.end_macro\noutside: nop\n")
            val outer = descendants(nested.node, "MACRO_DEFINITION").single()
            assertEquals(listOf("kept:"), descendants(outer, "LABEL").map { it.text }.toList())
            assertEquals("inside: .end_macro .include \"unfinished", descendants(outer, "INCLUDE_LINE").single().text)
        }
    }

    @Test
    fun `an include can replace a macro boundary line before macro assembly`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        directory.resolve("body.s").writeText("addiu \$t0,\$zero,7")
        for (prefix in listOf(".end_macro", ".macro replaced")) {
            val include = "$prefix .include \"body.s\""
            val source = ".macro emit\n$include\n.end_macro\nemit"
            assertAssembles(directory, source)
            readAction {
                val macro = descendants(assertParses(source).node, "MACRO_DEFINITION").single()
                assertEquals(include, descendants(macro, "INCLUDE_LINE").single().text)
                assertEquals(".end_macro", macro.lastChildNode.text)
            }
        }
        readAction {
            assertTrue(descendants(assertParses(".include 1").node, "INCLUDE_LINE").none())
        }
    }

    @Test
    fun `a rejected macro name keeps later labels and macro definitions in their own scopes`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val body = "outside: nop\n.macro emit (%value)\ninside: li \$t0, %value\n.end_macro\nemit(7)\n"
            val names = listOf("nop", "\$t0", ".word", "17", "%name", "")
            for (name in names) {
                val header = if (name.isEmpty()) "entry: .MaCrO,, # waiting" else "entry: .MaCrO,, $name (%arg)"
                val source = "$header\n$body"
                val entry = directory.resolve("bad-header.asm").also { it.writeText(source) }
                val context = project.get().service<MarsSessionService>().context
                val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
                val diagnostics = failure.errors().errorMessages.map { it.message }
                val expected = if (name.isEmpty()) "\".MaCrO\" directive requires at least one argument." else "Invalid Macro name \"$name\""
                assertEquals(listOf(expected), diagnostics, header)

                readAction {
                    val file = assertParses(source)
                    val issue = mipsSourceTimeline(file).macroBoundaryProblems(file).single()
                    assertEquals(if (name.isEmpty()) Kind.MISSING_NAME else Kind.INVALID_NAME, issue.kind)
                    assertEquals(name.ifEmpty { ".MaCrO" }, file.findElementAt(issue.offset)?.text)
                    val macro = descendants(file.node, "MACRO_DEFINITION").single()
                    assertEquals(listOf("inside:"), descendants(macro, "LABEL").map { it.text }.toList())
                    assertEquals(listOf("emit (%value)"), descendants(file.node, "MACRO_SIGNATURE").map { it.text }.toList())
                    assertEquals(
                        listOf("entry:", "outside:"),
                        descendants(file.node, "LABEL").filter { it.startOffset < macro.startOffset }.map { it.text }.toList(),
                    )
                    assertEquals("emit(7)", descendants(file.node, "OPERATION_CANDIDATE").single().text)
                }
            }
        }

    @Test
    fun `malformed macro parameters preserve the header prefix and body scope`() = timeoutRunBlocking<Unit> {
        readAction {
            val rejected = mapOf("7)" to "7", "-17)" to "-17", "+ 17)" to "+", "@)" to "@", "%)" to "%",
                "\"oops\" .eqv PHANTOM 7" to "\"oops\"")
            for ((tail, badToken) in rejected) {
                val source = ".macro emit (%value, $tail\nbody: li \$t0, %value\n.end_macro\nemit(7)\n"
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("macro.asm", MipsFileType, source)
                assertEquals(source, file.node.text)
                val macro = descendants(file.node, "MACRO_DEFINITION").single()
                assertEquals(listOf("body:"), descendants(macro, "LABEL").map { it.text }.toList(), tail)
                assertEquals(listOf("%value"), descendants(macro, "MACRO_PARAMETER").map { it.text }.toList())
                assertEquals(".end_macro", macro.lastChildNode.text)
                assertTrue(descendants(file.node, "EQUIVALENCE").none())
                val error = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).single()
                assertEquals(badToken, error.text, tail)
                assertEquals("Invalid MARS macro parameter", error.errorDescription, tail)
                assertEquals(listOf("emit(7)"), descendants(file.node, "OPERATION_CANDIDATE").map { it.text }.toList())
            }
        }
    }

    @Test
    fun `bare percent is invalid in the assembler and editor without closing the macro`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = ".macro emit (%value)\nli \$t0, %\nbody: nop\n.end_macro\nemit(7)"
            val entry = directory.resolve("unfinished.asm").also { it.writeText(source) }
            val context = project.get().service<MarsSessionService>().context
            val failure = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            assertTrue(failure.errors().errorMessages.any { it.message.endsWith("Invalid language element: %") })
            readAction {
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("unfinished.asm", MipsFileType, source)
                val percent = file.findElementAt(source.indexOf("%\n"))!!
                assertEquals(MipsTypes.psi("BAD_CHARACTER"), percent.node.elementType)
                assertEquals("%", percent.text)
                val macro = descendants(file.node, "MACRO_DEFINITION").single()
                assertEquals("body:", descendants(macro, "LABEL").single().text)
                assertEquals("emit(7)", descendants(file.node, "OPERATION_CANDIDATE").single().text)
            }
        }

    @Test
    fun `a nested macro opener does not consume the outer terminator`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .macro outer (%value)
            .macro inner (%other)
            inside: li ${'$'}t0, %value
            .end_macro
            outside: nop
            outer(7)
        """.trimIndent()
        val entry = directory.resolve("nested.asm").also { it.writeText(source) }
        val context = project.get().service<MarsSessionService>().context
        val error = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
        val messages = error.errors().errorMessages.map { it.message }
        assertTrue("Nested macros are not allowed" in messages, messages.toString())
        readAction {
            val openers = listOf(
                ".macro inner (%other)" to emptyList(),
                ".macro" to emptyList(),
                ".macro .word" to emptyList(),
                ".macro .set" to emptyList(),
                ".macro \$t0" to emptyList(),
                "nested_label: .macro inner" to listOf("nested_label:"),
                "first_label: nested_label: .macro inner" to listOf("first_label:"),
            )
            for ((opener, labels) in openers) {
                val updated = source.replace(".macro inner (%other)", opener)
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("nested.asm", MipsFileType, updated)
                val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription }
                assertEquals(when (opener) {
                    ".macro" -> listOf("Expected a macro name")
                    ".macro .word", ".macro .set", ".macro \$t0" -> listOf("Invalid MARS macro name")
                    else -> emptyList()
                }, errors, opener)
                val outer = descendants(file.node, "MACRO_DEFINITION").single()
                assertEquals(".end_macro", outer.lastChildNode.text)
                assertEquals(labels + "inside:", descendants(outer, "LABEL").map { it.text }.toList())
                assertEquals(opener, descendants(outer, "STATEMENT").first().text)
                val topLevel = file.children.filter { it.node.elementType == MipsTypes.psi("STATEMENT") }
                assertEquals(listOf("outside: nop", "outer(7)"), topLevel.drop(1).map { it.text })
            }
        }
    }

    @Test
    fun `malformed macro endings preserve body scope until a standalone terminator`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val valid = ".macro emit\nbody: addiu \$t0, \$zero, 7\n.end_macro,, # close\nemit\n"
        assertAssembles(directory, valid)
        readAction {
            val validMacro = descendants(assertParses(valid).node, "MACRO_DEFINITION").single()
            assertEquals(listOf("body:"), descendants(validMacro, "LABEL").map { it.text }.toList())
            for (suffix in listOf("stray", ")", ".eqv NOT_A_DEFINITION 7")) {
                val broken = valid.replace("body:", ".end_macro $suffix\nbody:")
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("macro.asm", MipsFileType, broken)
                assertEquals(broken, file.node.text)
                val macro = descendants(file.node, "MACRO_DEFINITION").single()
                assertEquals(listOf("body:"), descendants(macro, "LABEL").map { it.text }.toList(), suffix)
                assertEquals(broken.lastIndexOf(".end_macro"), macro.lastChildNode.startOffset, suffix)
                val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
                assertTrue(errors.isNotEmpty(), suffix)
                val malformedLine = broken.indexOf(".end_macro") until broken.indexOf("body:")
                assertTrue(errors.all { it.textOffset in malformedLine }, errors.map { it.textOffset to it.errorDescription }.toString())
                assertTrue(descendants(file.node, "EQUIVALENCE").none(), suffix)
                assertEquals(listOf("emit"), descendants(file.node, "OPERATION_CANDIDATE").map { it.text }.toList())
            }
        }
    }

    @Test
    fun `assembler commas remain punctuation without blocking native syntax`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            , , # separators alone
            ,,.eqv ,, VALUE ,, 7 ,, # ignored trailing separators
            ,,.macro,, emit,,(,,%value,,,),,
              ,,addiu,, ${'$'}t0,,, ${'$'}zero,,,%value,,
            ,,.end_macro,,,
            ,,.data,,
            ,,.word,, 1,, -2,, +3,,
            ,,.text,,
            ,,entry,, :,, emit,,(,,VALUE,,),,
            ,,,
        """.trimIndent()
        assertAssembles(directory, source)
        val memory = project.get().service<MarsSessionService>().context.memory
        assertEquals(listOf(1, -2, 3), (0..2).map { memory.getWordNoNotify(memory.dataBaseAddress + it * 4) })
        readAction {
            val file = assertParses(source)
            assertEquals(source.count { it == ',' }, descendants(file.node, "COMMA").count())
            assertEquals(listOf("entry,, :"), descendants(file.node, "LABEL").map { it.text }.toList())
            assertEquals(listOf("${'$'}t0", "${'$'}zero", "%value", "1", "-2", "+3"),
                descendants(file.node, "OPERAND").map { it.text }.toList())
            assertEquals(listOf("(", "VALUE", ")"), descendants(file.node, "INVOCATION_TOKEN").map { it.text }.toList())
            val adjacent = assertParses(".word 1, (2)\n")
            assertEquals(2, descendants(adjacent.node, "OPERAND").count())
            assertTrue(descendants(adjacent.node, "ADDRESS_EXPR").none())
        }
    }

    @Test
    fun `comma free signed data has the same operand boundaries as the assembler`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .data
            values: .word 1 -2 +3
                4-5+6
                'a'-7
            .float 1.25-2.5+3e-1
            .text
            addiu ${'$'}t0 ${'$'}zero-2
        """.trimIndent()
        assertAssembles(directory, source, 0x2408fffe)
        val memory = project.get().service<MarsSessionService>().context.memory
        val expected = listOf(1, -2, 3, 4, -5, 6, 'a'.code, -7, 1.25f.toBits(), (-2.5f).toBits(), 0.3f.toBits())
        assertEquals(expected, expected.indices.map { memory.getWordNoNotify(memory.dataBaseAddress + it * 4) })
        readAction {
            val file = assertParses(source)
            assertEquals(listOf("1", "-2", "+3", "4", "-5", "+6", "'a'", "-7", "1.25", "-2.5", "+3e-1", "\$t0", "\$zero", "-2"),
                descendants(file.node, "OPERAND").map { it.text }.toList())
            assertTrue(descendants(file.node, "ADD_EXPR").none())
        }
    }

    @Test
    fun `detached signs in data repetitions remain separate from the repeated value`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .data
                .word 0
                + 7:2 # The first token is ignored by the four-token repetition form.
                entry: -, -3 : 2
                .float 0
                + .5:2
                .macro fill
                .word 0
                -, 3:2
                .end_macro
                fill
                .text
                nop
            """.trimIndent()
            assertAssembles(directory, source, expectedInstruction = 0)
            val memory = project.get().service<MarsSessionService>().context.memory
            val expected = listOf(0, 7, 7, -3, -3, 0f.toBits(), .5f.toBits(), .5f.toBits(), 0, 3, 3)
            assertEquals(expected, expected.indices.map { memory.getWordNoNotify(memory.dataBaseAddress + it * Int.SIZE_BYTES) })
            readAction {
                val file = assertParses(source)
                val repeated = descendants(file.node, "DATA_CONTINUATION").filter {
                    descendants(it, "REPETITION").any()
                }.toList()
                assertEquals(listOf("+", "-", "+", "-"), repeated.map { it.firstChildNode.text })
                assertEquals(listOf("7:2", "-3 : 2", ".5:2", "3:2"), repeated.map {
                    descendants(it, "OPERAND").single().text
                })
                val ordinary = assertParses(".word +7:2\n-8:2\n+9\n")
                assertEquals(listOf("+7:2", "-8:2", "+9"), descendants(ordinary.node, "OPERAND").map { it.text }.toList())
            }
        }

    @Test
    fun `continued repetitions distinguish discarded literals from values`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .data
                .word 0
                99 7:2
                -17 +8:2
                'A' -9:2
                "ignored" 10:2
                row: +11 -12:2
                5 6
                .macro emit
                -1 -2:2
                .end_macro
                emit
                .text
                nop
            """.trimIndent()
            assertAssembles(directory, source, expectedInstruction = 0)
            val memory = project.get().service<MarsSessionService>().context.memory
            val expected = listOf(0, 7, 7, 8, 8, -9, -9, 10, 10, -12, -12, 5, 6, -2, -2)
            assertEquals(expected, expected.indices.map { memory.getWordNoNotify(memory.dataBaseAddress + it * Int.SIZE_BYTES) })
            readAction {
                val file = assertParses(source)
                assertEquals(listOf("99", "-17", "'A'", "\"ignored\"", "+11", "-1"),
                    descendants(file.node, "DATA_REPEAT_PREFIX").map { it.text }.toList())
                val continuations = descendants(file.node, "DATA_CONTINUATION").toList()
                assertEquals(listOf(
                    listOf("7:2"), listOf("+8:2"), listOf("-9:2"), listOf("10:2"), listOf("-12:2"), listOf("5", "6"), listOf("-2:2"),
                ), continuations.map { descendants(it, "OPERAND").map { operand -> operand.text }.toList() })
                val incomplete = assertParses(".word 0\n9 7:2 3\n")
                assertTrue(descendants(incomplete.node, "DATA_REPEAT_PREFIX").none())
            }
        }

    @Test
    fun `data repetition aliases preserve detached signs and adjacent signs keep their value`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            directory.resolve("repetitions.inc").writeText(".eqv REPEAT 7:2\n.eqv SUFFIX :2\n.eqv VALUE 5\n")
            val source = """
                .include "repetitions.inc"
                .data
                .word 0
                + REPEAT
                label: --3 SUFFIX
                +VALUE
                .macro fill (%value, %separator, %count)
                -, %value %separator %count
                .end_macro
                fill(7, :, 2)
                .text
                nop
            """.trimIndent()
            assertAssembles(directory, source, expectedInstruction = 0)
            val memory = project.get().service<MarsSessionService>().context.memory
            val expected = listOf(0, 7, 7, -3, -3, 5, 7, 7)
            assertEquals(expected, expected.indices.map { memory.getWordNoNotify(memory.dataBaseAddress + it * Int.SIZE_BYTES) })
            readAction {
                val file = assertParses(source)
                assertEquals(listOf("+", "-", "-"), descendants(file.node, "DATA_REPEAT_PREFIX").map { it.text }.toList())
                val first = descendants(file.node, "DATA_CONTINUATION").first()
                assertEquals("REPEAT", descendants(first, "OPERAND").single().text)
                assertEquals(listOf("+VALUE"), descendants(file.node, "UNARY_EXPR").map { it.text }.filter { it.startsWith('+') }.toList())
                val incomplete = assertParses(".word 1\n+ 7\n- 8:VAL\n+VALUE\n")
                assertEquals(listOf("-"), descendants(incomplete.node, "DATA_REPEAT_PREFIX").map { it.text }.toList())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["Default", "CompactDataAtZero", "CompactTextAtZero"])
    // Each layout assembles the full catalog and all spelling variants with both delay-slot settings.
    fun `all instruction formats agree with the assembler across separators and aliases`(
        layoutId: String, @TempDir directory: Path,
    ) = timeoutRunBlocking<Unit>(45.seconds) {
        val layout = MarsMemoryLayout.entries.single { it.identifier == layoutId }
        val memory = MemoryConfigurations.createConfiguration(layoutId)
        val instructions = InstructionSet().also { it.populate() }.instructionList
        assertTrue(instructions.size > 155, "The basic and pseudo-instruction catalogs must both be populated")
        val tokens = instructions.map { instruction ->
            val list = instruction.tokenList
            List(list.size()) { index -> list[index] }
        }
        val labels = tokens.flatMap { row -> row.filter { it.type == TokenTypes.IDENTIFIER }.map { it.value } }.toSortedSet()
        val header = listOf(".text") + labels.map { "$it:" }
        val examples = instructions.map { it.exampleFormat }
        val separator = ",\t,,"
        val commaHeavy = tokens.map { row ->
            val fields = listOf(row.first().value.uppercase()) + row.drop(1).map { it.value }
            fields.joinToString(separator, prefix = "\t,,", postfix = ",, # assembler ignores repeated separators")
        }
        val immediateSpellings: Map<String, (Int) -> String> = linkedMapOf(
            "signed-hex" to { "+0x${it.toString(16)}" },
            "octal" to { "0${it.toString(8)}" },
            "escaped-character" to { "'\\${it.toString(8).padStart(3, '0')}'" },
        )
        val rewrittenImmediates = immediateSpellings.mapValues { (_, spell) ->
            tokens.map { row ->
                row.joinToString(separator) { token ->
                    if (TokenTypes.isIntegerTokenType(token.type)) {
                        val value = Binary.stringToInt(token.value)
                        if (value in 0..255) spell(value) else token.value
                    } else token.value
                }
            }
        }
        val aliasDefinitions = tokens.mapIndexed { index, row -> ".eqv OP_$index ${row.first().value}" }
        val aliasCalls = tokens.mapIndexed { index, row ->
            row.drop(1).joinToString(separator, prefix = "OP_$index$separator") { it.value }
        }
        val punctuation = setOf(TokenTypes.LEFT_PAREN, TokenTypes.RIGHT_PAREN, TokenTypes.PLUS, TokenTypes.MINUS)
        val operandAliases = tokens.flatMap { row -> row.drop(1).filter { it.type !in punctuation }.map { it.value } }
            .distinct().mapIndexed { index, spelling -> spelling to "OPERAND_$index" }.toMap()
        val operandDefinitions = operandAliases.map { (spelling, alias) -> ".eqv $alias $spelling" }
        val aliasedOperands = tokens.map { row ->
            row.drop(1).joinToString(separator, prefix = row.first().value + separator) {
                operandAliases[it.value] ?: it.value
            }
        }
        val mixedOperands = (1 until tokens.maxOf { it.size }).map { aliasPosition ->
            tokens.map { row ->
                row.mapIndexed { index, token ->
                    if (index == aliasPosition) operandAliases[token.value] ?: token.value else token.value
                }.joinToString(separator)
            }
        }
        val macroOperandCalls = tokens.mapIndexed { index, row ->
            row.drop(1).filter { it.type !in punctuation }.joinToString(",", prefix = "operands_$index(", postfix = ")") { it.value }
        }
        val macroOperandDefinitions = listOf("%", "\$").map { sigil ->
            tokens.flatMapIndexed { index, row ->
                val parameters = ArrayList<String>()
                val body = row.drop(1).joinToString(separator, prefix = row.first().value + separator) { token ->
                    if (token.type in punctuation) token.value
                    else "${sigil}arg${parameters.size}".also(parameters::add)
                }
                listOf(".macro operands_$index (${parameters.joinToString(",")})", body, ".end_macro")
            }
        }
        val macroDefinitions = tokens.map { it.size }.toSortedSet().flatMap { arity ->
            val parameters = (1..arity).map { "%arg$it" }
            listOf(".macro emit_$arity (${parameters.joinToString(",")})", parameters.joinToString(" "), ".end_macro")
        }
        val macroCalls = tokens.map { row ->
            row.joinToString(",", prefix = "emit_${row.size}(", postfix = ")") { it.value }
        }
        val context = project.get().service<MarsSessionService>().context
        // Compact text has a 4 KiB segment. The catalog's independent examples
        // can share their label definitions across smaller assembly batches.
        val batchSize = 128
        fun assemble(name: String, prefix: List<String>, lines: List<String>): List<Pair<Int, Int>> {
            val words = lines.chunked(batchSize).flatMapIndexed { batch, examples ->
                val source = (prefix + examples).joinToString("\n", postfix = "\n")
                val entry = directory.resolve("$name-$batch.asm").also { it.writeText(source) }
                val program = try {
                    context.assemble(listOf(entry), null, true, false)
                } catch (failure: ProcessingException) {
                    throw AssertionError("$layoutId, $name batch $batch\n${failure.errors().generateErrorReport()}", failure)
                }
                val origins = program.sourceMap().origins()
                assertEquals(memory.textBaseAddress, program.machineList.first().address, name)
                program.machineList.map { statement ->
                    val origin = origins.getValue(statement.address)
                    val invocation = (origin.caller() ?: origin).location().line() - prefix.size
                    statement.binaryStatement to (batch * batchSize + invocation)
                }
            }
            assertEquals((1..lines.size).toSet(), words.map { it.second }.toSet(), name)
            return words
        }
        for (delayed in listOf(false, true)) {
            MarsExecutionOptions(delayedBranching = delayed, memoryLayout = layout).applyForAssembly(context)
            val canonicalWords = assemble("canonical", header, examples)
            assertEquals(canonicalWords, assemble("comma-heavy", header, commaHeavy), "Separators with delay slots $delayed")
            for ((spelling, lines) in rewrittenImmediates) {
                assertEquals(canonicalWords, assemble(spelling, header, lines), "$spelling immediates with delay slots $delayed")
            }
            assertEquals(canonicalWords, assemble("aliased", aliasDefinitions + header, aliasCalls), "Aliases with delay slots $delayed")
            assertEquals(canonicalWords, assemble("operand-aliases", operandDefinitions + header, aliasedOperands),
                "Operand aliases with delay slots $delayed")
            for ((index, mixed) in mixedOperands.withIndex()) {
                assertEquals(canonicalWords, assemble("mixed-operands", operandDefinitions + header, mixed),
                    "Alias at token ${index + 1} with delay slots $delayed")
            }
            for (definitions in macroOperandDefinitions) {
                assertEquals(canonicalWords, assemble("operand-formals", definitions + header, macroOperandCalls),
                    "Operand formals with delay slots $delayed")
            }
            assertEquals(canonicalWords, assemble("macros", macroDefinitions + header, macroCalls), "Macros with delay slots $delayed")
        }

        readAction {
            fun checkOperations(prefix: List<String>, lines: List<String>, kind: String, expected: List<String>): List<ASTNode> {
                val file = assertParses((prefix + lines).joinToString("\n", postfix = "\n"))
                val start = prefix.sumOf { it.length + 1 }
                val operations = descendants(file.node, kind).filter { it.startOffset >= start }.toList()
                assertEquals(
                    expected.map(String::lowercase),
                    operations.map { it.firstChildNode.text.lowercase() },
                    kind,
                )
                return operations
            }
            val names = instructions.map { it.name }
            val written = checkOperations(header, examples, "INSTRUCTION", names)
            checkOperations(aliasDefinitions + header, aliasCalls, "OPERATION_CANDIDATE", instructions.indices.map { "OP_$it" })
            checkOperations(macroDefinitions + header, macroCalls, "OPERATION_CANDIDATE", tokens.map { "emit_${it.size}" })

            val tokenizer = Tokenizer()
            fun operandFieldRanges(instruction: ASTNode, fields: List<TextRange>): List<IntRange> {
                return descendants(instruction, "OPERAND").toList().map { operand ->
                    val start = operand.startOffset - instruction.startOffset
                    val end = start + operand.textLength
                    val first = fields.indexOfFirst { it.startOffset == start }
                    val last = fields.indexOfFirst { it.endOffset == end }
                    assertTrue(first >= 0 && last >= first, "Operand '${operand.text}' cuts a field in '${instruction.text}'")
                    first..last
                }
            }
            val expectedRanges = written.map { instruction ->
                val physicalTokens = tokenizer.tokenizeLine(null, 1, instruction.text, false)
                val fields = List(physicalTokens.size()) { index ->
                    TextRange(physicalTokens[index].startPos - 1, physicalTokens[index].endPos - 1)
                }
                operandFieldRanges(instruction, fields)
            }
            fun checkOperandBoundaries(rewritten: List<ASTNode>) {
                for ((index, instruction) in rewritten.withIndex()) {
                    // Use the authored fields. Before substitution, the core may split a
                    // signed immediate following an alias into a sign and a magnitude.
                    var offset = 0
                    val fields = instruction.text.split(separator).map { field ->
                        TextRange.from(offset, field.length).also { offset = it.endOffset + separator.length }
                    }
                    assertEquals(tokens[index].size, fields.size, instruction.text)
                    assertEquals(expectedRanges[index], operandFieldRanges(instruction, fields),
                        "${written[index].text} / ${instruction.text}")
                }
            }
            checkOperandBoundaries(checkOperations(header, commaHeavy, "INSTRUCTION", names))
            for (lines in rewrittenImmediates.values) {
                checkOperandBoundaries(checkOperations(header, lines, "INSTRUCTION", names))
            }
            checkOperandBoundaries(checkOperations(operandDefinitions + header, aliasedOperands, "INSTRUCTION", names))
            for (mixed in mixedOperands) {
                checkOperandBoundaries(checkOperations(operandDefinitions + header, mixed, "INSTRUCTION", names))
            }
            for (definitions in macroOperandDefinitions) {
                checkOperandBoundaries(checkOperations(header, definitions + macroOperandCalls, "INSTRUCTION", names))
            }
        }
    }

    @Test
    fun `bare machine forms retain numeric register operands in every MARS file type`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val instructions = InstructionDefaults.get().instructionList.filterIsInstance<BasicInstruction>()
            val integerRegisters = setOf(TokenTypes.REGISTER_NAME, TokenTypes.REGISTER_NUMBER)
            val operands = instructions.map { instruction ->
                val tokens = instruction.tokenList
                (1 until tokens.size()).map { tokens[it] }
            }
            val labels = operands.flatten().filter { it.type == TokenTypes.IDENTIFIER }.map { it.value }.toSortedSet()
            val registerTokens = operands.map { row -> row.filter { it.type in integerRegisters || it.type == TokenTypes.FP_REGISTER_NAME } }
            val context = project.get().service<MarsSessionService>().context
            fun assemble(name: String, source: String): List<Int> {
                val path = directory.resolve(name).also { it.writeText(source) }
                val program = try {
                    context.assemble(listOf(path), null, false, false)
                } catch (failure: ProcessingException) {
                    throw AssertionError("$name\n${failure.errors().generateErrorReport()}", failure)
                }
                return program.machineList.map { it.binaryStatement }
            }
            fun source(lines: List<String>) = (listOf(".text") + labels.map { "$it:" } + lines).joinToString("\n", postfix = "\n")

            MarsExecutionOptions(allowPseudoInstructions = false).applyForAssembly(context)
            val canonical = assemble("canonical.asm", source(instructions.map { it.exampleFormat }))
            assertEquals(instructions.size, canonical.size)
            MarsExecutionOptions(allowPseudoInstructions = false, bareMachine = true).applyForAssembly(context)

            val spellings = listOf<(Int) -> String>(
                { it.toString() }, { "0" + it.toString(8) }, { "0x" + it.toString(16) },
            )
            for ((variant, spelling) in spellings.withIndex()) {
                val registers = registerTokens.map { row ->
                    row.map { token ->
                        if (token.type == TokenTypes.FP_REGISTER_NAME) {
                            "\$f" + spelling(RegisterNames.floating(token.value).number)
                        } else {
                            "\$" + spelling(RegisterNames.integer(token.value).number)
                        }
                    }
                }
                val lines = instructions.mapIndexed { index, instruction ->
                    StringBuilder(instruction.exampleFormat).apply {
                        for ((token, name) in registerTokens[index].zip(registers[index]).asReversed()) {
                            replace(token.startPos - 1, token.endPos - 1, name)
                        }
                    }.toString()
                }
                val source = source(lines)
                for (suffix in listOf("asm", "s", "inc", "mars")) {
                    val name = "bare-$variant.$suffix"
                    assertEquals(canonical, assemble(name, source), name)
                    readAction {
                        val parsed = descendants(assertParses(source, name).node, "INSTRUCTION").toList()
                        assertEquals(lines, parsed.map { it.text }, name)
                        assertEquals(registers, parsed.map { instruction ->
                            descendants(instruction, "REGISTER").map { it.text }.toList()
                        }, name)
                    }
                }
            }
        }

    @Test
    fun `address operands follow the assembler with or without commas`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val header = ".eqv BASE \$sp\n.eqv DEST \$t0\n.eqv OFFSET + 4\n.data\ntarget: .word 0\nnop: .word 0\n.text\n"
        val canonical = """
            lw ${'$'}t0,(${'$'}sp)
            lwc1 ${'$'}f0,(${'$'}sp)
            lw ${'$'}t0,4(${'$'}sp)
            lw ${'$'}t0,target(${'$'}sp)
            lw ${'$'}t0,'D'(${'$'}sp)
            lw ${'$'}t0,8(${'$'}sp)
            lw ${'$'}t0,(${'$'}sp)
            lw ${'$'}t0,(${'$'}sp)
            lw ${'$'}t0,(${'$'}sp)
            lw ${'$'}t0,nop+ 4(${'$'}sp)
        """.trimIndent()
        val alternate = """
            lw ${'$'}t0(${'$'}sp)
            lwc1 ${'$'}f0(${'$'}sp)
            lw ${'$'}t0,4,,(${'$'}sp)
            lw ${'$'}t0,target,(${'$'}sp)
            lw ${'$'}t0,'D',(${'$'}sp)
            lw ${'$'}t0,8,,(,BASE,)
            lw DEST (${'$'}sp)
            lw DEST,(${'$'}sp)
            read: lw DEST,,,(${'$'}sp)
            lw ${'$'}t0,nop OFFSET(${'$'}sp)
        """.trimIndent()
        val context = project.get().service<MarsSessionService>().context
        fun assemble(text: String): List<Int> {
            val entry = directory.resolve("addresses.asm").also { it.writeText(header + text + "\n") }
            return context.assemble(listOf(entry), null, true, false).machineList.map { it.binaryStatement }
        }
        assertEquals(assemble(canonical), assemble(alternate))
        readAction {
            val root = assertParses(header + alternate + "\n").node
            val instructions = descendants(root, "INSTRUCTION").toList()
            assertEquals(
                listOf(
                    listOf("\$t0", "(\$sp)"), listOf("\$f0", "(\$sp)"), listOf("\$t0", "4,,(\$sp)"),
                    listOf("\$t0", "target,(\$sp)"), listOf("\$t0", "'D',(\$sp)"), listOf("\$t0", "8,,(,BASE,)"),
                    listOf("DEST", "(\$sp)"), listOf("DEST", "(\$sp)"),
                    listOf("DEST", "(\$sp)"), listOf("\$t0", "nop", "OFFSET(\$sp)"),
                ),
                instructions.map { descendants(it, "OPERAND").map { operand -> operand.text }.toList() },
            )
            assertEquals(listOf("4,,(\$sp)", "target,(\$sp)", "'D',(\$sp)", "8,,(,BASE,)", "OFFSET(\$sp)"),
                descendants(root, "ADDRESS_EXPR").map { it.text }.toList())
        }
        for (formal in listOf("%dest", "\$dest")) {
            val source = ".macro load ($formal)\nlw $formal (\$sp)\n.end_macro\nload(\$t0)\n"
            assertAssembles(directory, source, 0x8fa80000.toInt())
            readAction {
                val instruction = descendants(assertParses(source).node, "INSTRUCTION").single()
                assertEquals(listOf(formal, "(\$sp)"), descendants(instruction, "OPERAND").map { it.text }.toList())
            }
        }
    }

    @Test
    fun `commas preserve label offsets and their optional base registers`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val header = ".eqv DELTA 4\n.data\ntarget: .word 0\n\$target: .word 0\nnop: .word 0\n.text\n"
        val operands = listOf(
            "target,,+4",
            "\$target,+4",
            "target,,+,,DELTA,,(\$sp)",
            "nop,,+ 4,,(\$sp)",
        )
        val canonical = listOf(
            "la \$t0,target+4",
            "la \$t0,\$target+4",
            "lw \$t0,target+4(\$sp)",
            "lw \$t0,nop+ 4(\$sp)",
        )
        val alternate = canonical.zip(operands) { line, operand ->
            "${line.substringBefore(',')},$operand"
        }
        val context = project.get().service<MarsSessionService>().context
        fun assemble(lines: List<String>): List<Int> {
            val entry = directory.resolve("label-offsets.asm").also { it.writeText(header + lines.joinToString("\n", postfix = "\n")) }
            return context.assemble(listOf(entry), null, true, false).machineList.map { it.binaryStatement }
        }
        assertEquals(assemble(canonical), assemble(alternate))
        readAction {
            val source = header + alternate.joinToString("\n", postfix = "\n")
            val instructions = descendants(assertParses(source).node, "INSTRUCTION").toList()
            assertEquals(
                operands.map { listOf("\$t0", it) },
                instructions.map { descendants(it, "OPERAND").map { operand -> operand.text }.toList() },
            )
            assertEquals(operands, instructions.flatMap { descendants(it, "ADD_EXPR").map { offset -> offset.text } })
            assertEquals(listOf("DELTA,,(\$sp)", "4,,(\$sp)"),
                instructions.flatMap { descendants(it, "ADDRESS_EXPR").map { address -> address.text } })
            val signed = assertParses("la \$t0,nop,+4\n")
            assertEquals(listOf("\$t0", "nop", "+4"), descendants(signed.node, "OPERAND").map { it.text }.toList())
        }
    }

    @Test
    fun `adjacent plus in a macro label offset stays with the address operand`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .macro load (%out, %label, %reg)
                    la %out,%label+4
                    lw %out,%label,+4(%reg)
                labeled: lw %out,%label+4(%reg)
                    lw %out,+4(%reg)
                    addiu %out,%reg,+4
                .end_macro
                .data
                values: .word 0, 7
                .text
                load(${'$'}t0, values, ${'$'}sp)
            """.trimIndent()
            val context = project.get().service<MarsSessionService>().context
            fun assemble(text: String): List<Int> {
                val entry = directory.resolve("adjacent-offset.asm").also { it.writeText(text) }
                return context.assemble(listOf(entry), null, true, false).machineList.map { it.binaryStatement }
            }
            val spaced = source.replace("%label+4", "%label + 4").replace("%label,+4", "%label, + 4")
            assertEquals(assemble(spaced), assemble(source))
            readAction {
                val root = assertParses(source).node
                assertEquals(
                    listOf(
                        listOf("%out", "%label+4"),
                        listOf("%out", "%label,+4(%reg)"),
                        listOf("%out", "%label+4(%reg)"),
                        listOf("%out", "+4(%reg)"),
                        listOf("%out", "%reg", "+4"),
                    ),
                    descendants(root, "INSTRUCTION").toList().map { instruction ->
                        descendants(instruction, "OPERAND").map { it.text }.toList()
                    },
                )
            }
        }

    @Test
    fun `signed immediates after register aliases stay separate from the register`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val t0 = "\$t0"
            val t1 = "\$t1"
            val zero = "\$zero"
            val src = "\$src"
            val definitions = """
                .eqv SRC $zero
                .eqv REGS $t0, $zero
                .eqv DEST $t1
                .eqv N 1
                .macro emit ($src)
                    addiu $t0,$src-4
                .end_macro
                .data
                values: .word N,+4
                2 N,+4
                .text
            """.trimIndent()
            val aliases = """
                addiu $t0,SRC,+4
                addiu $t0,SRC -4
                addiu REGS,+4
                la DEST,+4
                la $t0,values,+4
                emit($zero)
            """.trimIndent()
            val canonical = """
                addiu $t0, $zero, +4
                addiu $t0, $zero, -4
                addiu $t0, $zero, +4
                la $t1, +4
                la $t0, values+4
                addiu $t0, $zero, -4
            """.trimIndent()
            val context = project.get().service<MarsSessionService>().context
            fun assemble(text: String): List<Int> {
                val entry = directory.resolve("signed-register-alias.asm").also { it.writeText("$definitions\n$text\n") }
                return context.assemble(listOf(entry), null, true, false).machineList.map { it.binaryStatement }
            }
            assertEquals(assemble(canonical), assemble(aliases))
            val memory = context.memory
            assertEquals(listOf(1, 4, 2, 1, 4), List(5) { memory.getWordNoNotify(memory.dataBaseAddress + 4 * it) })
            readAction {
                val root = assertParses("$definitions\n$aliases\n").node
                assertEquals(
                    listOf(
                        listOf(t0, src, "-4"), listOf(t0, "SRC", "+4"), listOf(t0, "SRC", "-4"),
                        listOf("REGS", "+4"), listOf("DEST", "+4"), listOf("\$t0", "values,+4"),
                    ),
                    descendants(root, "INSTRUCTION").toList().map { instruction ->
                        descendants(instruction, "OPERAND").map { it.text }.toList()
                    },
                )
                val data = descendants(root, "DIRECTIVE_STATEMENT").single { it.text.startsWith(".word") }
                assertEquals(listOf("N", "+4"), descendants(data, "OPERAND").map { it.text }.toList())
                val continuation = descendants(root, "DATA_CONTINUATION").single()
                assertEquals(listOf("2", "N", "+4"), descendants(continuation, "OPERAND").map { it.text }.toList())
            }
        }

    @Test
    fun `named floating point values use numeric PSI and preserve assembler output`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = ".data\n.double NaN, Infinity, -1.2e-5, 0x1.8p2, 0X1.EP2, .5f\n.float NaN\n.text\nnop\n"
        assertAssembles(directory, source, 0)
        val memory = project.get().service<MarsSessionService>().context.memory
        val base = memory.dataBaseAddress
        val doubles = listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.2e-5, 6.0, 7.5, 0.5)
        for ((i, value) in doubles.withIndex()) {
            val bits = value.toBits()
            assertEquals(bits.toInt(), memory.getWordNoNotify(base + i * 8))
            assertEquals((bits ushr 32).toInt(), memory.getWordNoNotify(base + i * 8 + 4))
        }
        assertEquals(Float.NaN.toBits(), memory.getWordNoNotify(base + doubles.size * 8))
        readAction {
            val file = assertParses(source)
            assertEquals(listOf("NaN", "Infinity", "1.2e-5", "0x1.8p2", "0X1.EP2", ".5f", "NaN"),
                descendants(file.node, "LITERAL_EXPR").map { it.text }.toList())
            assertTrue(descendants(file.node, "REFERENCE_EXPR").none())
            val labels = assertParses("nan: nop\ninfinity: nop\nInfinityCount: nop\n")
            assertEquals(listOf("nan:", "infinity:", "InfinityCount:"), descendants(labels.node, "LABEL").map { it.text }.toList())
        }
    }

    @Test
    fun `assembly numeric tokens preserve accepted legacy spellings and recover malformed candidates`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val wordSpellings = listOf("0X80000000", "0xffffffff", "4294967295", "077", "09", "١٢")
            val doubleSpellings = listOf("\u000c1.5", "\u000bNaN", "\u000b1.25\u000c", "0x1p2")
            val valid = ".data\n.word ${wordSpellings.joinToString()}\n.double ${doubleSpellings.joinToString()}\n.text\nnop\n"
            val entry = directory.resolve("number-spellings.asm").also { it.writeText(valid) }
            val context = project.get().service<MarsSessionService>().context
            val program = context.assemble(listOf(entry), null, true, false)
            assertEquals(listOf(0), program.machineList.map { it.binaryStatement })
            val memory = context.memory
            val base = memory.dataBaseAddress
            assertEquals(listOf(Int.MIN_VALUE, -1, -1, 63, 9, 12), wordSpellings.indices.map {
                memory.getWordNoNotify(base + it * Int.SIZE_BYTES)
            })
            assertEquals(listOf(1.5, Double.NaN, 1.25, 4.0).map(Double::toBits), doubleSpellings.indices.map {
                val address = base + wordSpellings.size * Int.SIZE_BYTES + it * Double.SIZE_BYTES
                val low = memory.getWordNoNotify(address).toUInt().toLong()
                val high = memory.getWordNoNotify(address + Int.SIZE_BYTES).toLong()
                (high shl Int.SIZE_BITS) or low
            })
            readAction {
                val file = assertParses(valid)
                assertEquals(wordSpellings + doubleSpellings, descendants(file.node, "LITERAL_EXPR").map { it.text }.toList())
            }

            val malformed = listOf("0xG", "0x", "1oops", "1e", "0b101", "0x123456789")
            val invalid = ".data\n.word ${malformed.joinToString()}\n.text\nnext: nop\n"
            entry.writeText(invalid)
            val diagnostics = assertThrows<ProcessingException> { context.assemble(listOf(entry), null, true, false) }
            for (spelling in malformed) {
                assertTrue(diagnostics.errors().errorMessages.any { it.message.endsWith("Invalid language element: $spelling") })
            }
            readAction {
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("invalid-numbers.asm", MipsFileType, invalid)
                assertEquals(invalid, file.text)
                assertEquals(malformed, descendants(file.node, "BAD_CHARACTER").map { it.text }.toList())
                assertTrue(descendants(file.node, "LITERAL_EXPR").none())
                assertEquals("next:", descendants(file.node, "LABEL").single().text)
                assertEquals("nop", descendants(file.node, "OPERATION").single().text)
            }
        }

    @Test
    fun `malformed lexical candidates keep their full span and recover the following line`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = """
                .data
                .word 0x1e-5, 7
                .text
                name-2: nop
                bad%name: nop
                .eqv bad@name 7
                fake/.include "missing.asm"
                next: nop
            """.trimIndent()
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("exponent.asm", MipsFileType, source)
            assertEquals(source, file.text)
            assertEquals(listOf("0x1e-5", "name-2", "bad%name", "bad@name", "fake/.include"),
                descendants(file.node, "BAD_CHARACTER").map { it.text }.toList())
            assertEquals("next:", descendants(file.node, "LABEL").single().text)
            assertTrue(descendants(file.node, "INCLUDE_LINE").none())
            assertEquals("nop", descendants(file.node, "INSTRUCTION").last().text)
        }
    }

    @Test
    fun `malformed operands keep later arguments and replacement tokens on the same line`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = """
                .data
                .word 0x, 7
                .eqv VALUE @bad, 8
                .text
                .macro emit (%a, %b, %c)
                addiu ${'$'}t0, 1e, %b
                .end_macro
                emit(0x, ${'$'}t0, target)
                target: nop
            """.trimIndent()
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("editing.mars", MipsFileType, source)
            assertEquals(source, file.text)
            val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            assertEquals(listOf("0x", "@bad", "1e", "0x"), errors.map { it.text })
            assertTrue(errors.all { it.errorDescription == "Invalid MARS operand token" })
            assertEquals(listOf("0x", "7"), descendants(file.node, "OPERAND").map { it.text }.toList())
            assertEquals(listOf("@bad", "8"), descendants(file.node, "REPLACEMENT").map { it.text }.toList())
            assertEquals("\$t0, 1e, %b", descendants(file.node, "OPERAND_FRAGMENT").single().text)
            assertEquals(listOf("(", "0x", "\$t0", "target", ")"),
                descendants(file.node, "INVOCATION_TOKEN").map { it.text }.toList())
            assertEquals("target:", descendants(file.node, "LABEL").single().text)
        }
    }

    @Test
    fun `malformed signed operands retain the assembler token boundaries`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = ".data\n.word -0xG, 7\n.eqv VALUE 1,+0x, 8\n.eqv -0xG\n.text\n" +
                "emit(1, -1e, target)\nemit(label-0xG, target)\nemit(1, - 0xG, target)\ntarget: nop\n"
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("signed-editing.mars", MipsFileType, source)
            val tokenizer = Tokenizer()
            val badSpellings = source.lineSequence().flatMap { line ->
                val tokens = tokenizer.tokenizeLine(null, 1, line, false)
                (0 until tokens.size()).asSequence().map(tokens::get).filter { it.type == TokenTypes.ERROR }
                    .map { line.substring(it.startPos - 1, it.endPos - 1) }
            }.toList()
            assertEquals(listOf("-0xG", "+0x", "-0xG", "-1e", "0xG", "0xG"), badSpellings)
            assertEquals(badSpellings, PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.text })
            assertEquals(listOf("-0xG", "7"), descendants(file.node, "OPERAND").map { it.text }.toList())
            assertEquals(listOf("1", "+0x", "8"), descendants(file.node, "REPLACEMENT").map { it.text }.toList())
            assertEquals(listOf("(", "1", "-1e", "target", ")", "(", "label", "-", "0xG", "target", ")",
                "(", "1", "-", "0xG", "target", ")"),
                descendants(file.node, "INVOCATION_TOKEN").map { it.text }.toList())
            assertEquals("target:", descendants(file.node, "LABEL").single().text)
        }
    }

    @Test
    fun `malformed offsets retain their address and base register in PSI`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = "lw \$t0, -0x(\$sp)\nlw \$t1, 0x, (\$fp)\nnext: nop\n"
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("address-editing.mars", MipsFileType, source)
            assertEquals(source, file.text)
            val addresses = descendants(file.node, "ADDRESS_EXPR").toList()
            assertEquals(listOf("-0x(\$sp)", "0x, (\$fp)"), addresses.map { it.text })
            assertEquals(listOf("\$sp", "\$fp"), addresses.flatMap { descendants(it, "REGISTER").map { it.text }.toList() })
            assertEquals(listOf("-0x", "0x"), PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.text })
            assertEquals("next:", descendants(file.node, "LABEL").single().text)
        }
    }

    @Test
    fun `unfinished parenthesized operands retain their base and subsequent operands`() = timeoutRunBlocking<Unit> {
        val source = "lw \$t0, (\$sp, 7\nsw \$t0, 4(\$fp, 8\nlw \$t1, (\nsw \$t1, 4(\n" +
            "lw \$t1, ()\nsw \$t1, 4()\nnext: nop\n"
        readAction {
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("parenthesis-editing.mars", MipsFileType, source)
            assertEquals(source, file.text)
            assertEquals(listOf("(\$sp", "(", "()"), descendants(file.node, "GROUP_EXPR").map { it.text }.toList())
            assertEquals(listOf("4(\$fp", "4(", "4()"), descendants(file.node, "ADDRESS_EXPR").map { it.text }.toList())
            val instructions = descendants(file.node, "INSTRUCTION").toList()
            assertEquals(listOf("\$t0", "(\$sp", "7"), descendants(instructions[0], "OPERAND").map { it.text }.toList())
            assertEquals(listOf("\$t0", "4(\$fp", "8"), descendants(instructions[1], "OPERAND").map { it.text }.toList())
            assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isNotEmpty())
            assertEquals("next:", descendants(file.node, "LABEL").single().text)
            assertEquals("nop", instructions.last().text)
        }
    }

    @Test
    fun `punctuation in percent formals survives parsing and macro substitution`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val formal = "%value!@?.part"
            val source = ".macro emit ($formal)\naddiu \$t0, \$zero, $formal\n.end_macro\nemit(7)"
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                assertEquals(listOf(formal, formal), descendants(file.node, "PARAMETER").map { it.text }.toList())
            }
        }

    @Test
    fun `invalid statement heads preserve macro scope and later labels`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = ": 9 (\n.macro emit\n! 6 (\ninside: nop\n.end_macro\noutside: nop\n:"
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("recover.asm", MipsFileType, source)
            assertEquals(source, file.text)
            val macro = descendants(file.node, "MACRO_DEFINITION").single()
            assertEquals("inside:", descendants(macro, "LABEL").single().text)
            assertEquals(listOf("inside:", "outside:"), descendants(file.node, "LABEL").map { it.text }.toList())
            val allErrors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            val errors = allErrors.filter { it.errorDescription == "Expected a MIPS statement" }
            assertEquals(listOf(": 9 (", "! 6 (", ":"), errors.map { it.text },
                allErrors.joinToString { "${it.errorDescription} at ${it.textRange}: ${it.text}" })
        }
    }

    @Test
    fun `dot-prefixed names containing digits stay distinct from decimal literals`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv .1_count 7
            .macro .2_load (%value)
              addiu ${'$'}t0, ${'$'}zero, %value
            .end_macro
            .data
            .3_data: .float .5e-2
            .word .3_data
            .text
            .4_entry: .2_load(.1_count)
            la ${'$'}t1, .3_data
            j .4_entry
        """.trimIndent()
        val entry = directory.resolve("dotted-names.asm").also { it.writeText(source) }
        val context = project.get().service<MarsSessionService>().context
        val program = context.assemble(listOf(entry), null, true, false)
        assertEquals(0x24080007, program.machineList.first().binaryStatement)
        val memory = context.memory
        val base = memory.dataBaseAddress
        assertEquals(0.005f.toBits(), memory.getWordNoNotify(base))
        assertEquals(base, memory.getWordNoNotify(base + 4))
        readAction {
            val root = assertParses(source).node
            assertEquals(listOf(".3_data:", ".4_entry:"), descendants(root, "LABEL").map { it.text }.toList())
            assertEquals(".2_load (%value)", descendants(root, "MACRO_SIGNATURE").single().text)
            assertEquals(".2_load(.1_count)", descendants(root, "OPERATION_CANDIDATE").single().text)
            assertEquals(listOf(".3_data", ".3_data", ".4_entry"),
                descendants(root, "REFERENCE_EXPR").map { it.text }.filter { it.startsWith('.') }.toList())
            assertEquals(listOf(".5e-2"), descendants(root, "LITERAL_EXPR").map { it.text }.toList())
        }
    }

    @Test
    fun `Unicode include paths symbols and decimal digits preserve syntax and expansion origins`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val library = """
                .eqv 定数 ٧
                .macro 印字 (${'$'}量)
                  ADDIU ${'$'}t0, ${'$'}zero, ${'$'}量
                .end_macro
            """.trimIndent()
            val source = """
                .include "常用.inc"
                .data
                .數據: .word 定数
                .word .數據
                .text
                入口: 印字(定数)
                j 入口
            """.trimIndent()
            val entry = directory.resolve("入口.mars").also { it.writeText(source) }
            val include = directory.resolve("常用.inc").also { it.writeText(library) }
            val context = project.get().service<MarsSessionService>().context
            val program = context.assemble(listOf(entry), null, true, false)
            val expanded = program.machineList.first()
            assertEquals(0x24080007, expanded.binaryStatement)
            val origin = program.sourceMap().origins().getValue(expanded.address)
            assertEquals(include.toRealPath(), origin.location().file().toRealPath())
            assertEquals(3, origin.location().line())
            assertEquals(entry.toRealPath(), origin.caller().location().file().toRealPath())
            assertEquals(6, origin.caller().location().line())
            val base = context.memory.dataBaseAddress
            assertEquals(7, context.memory.getWordNoNotify(base))
            assertEquals(base, context.memory.getWordNoNotify(base + 4))

            readAction {
                val libraryRoot = assertParses(library, include.fileName.toString()).node
                assertEquals("٧", descendants(libraryRoot, "REPLACEMENT").single().text)
                assertEquals("\$量", descendants(libraryRoot, "MACRO_PARAMETER").single().text)
                val root = assertParses(source, entry.fileName.toString()).node
                assertEquals(".include \"常用.inc\"", descendants(root, "INCLUDE_LINE").single().text)
                assertEquals(listOf(".數據:", "入口:"), descendants(root, "LABEL").map { it.text }.toList())
                assertEquals("印字(定数)", descendants(root, "OPERATION_CANDIDATE").single().text)
            }
        }

    @Test
    fun `character literals may contain delimiters and assembler escapes`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val literals = listOf("','", "'#'", "':'", "'('", "')'", "'+'", "'-'", "'\"'", "' '",
            "'\\''", "'\\\\'", "'\\n'", "'\\0'", "'\\+77'", "'\\-00'", "'\\١٢٣'", "'\\377'", "'é'")
        val source = ".data\n.word ${literals.joinToString(", ")}\n.text\nnop\n"
        assertAssembles(directory, source, 0)
        val memory = project.get().service<MarsSessionService>().context.memory
        val characters = ",#:()+-\" '\\\n\u0000?\u0000Sÿé"
        assertEquals(characters.map(Char::code),
            literals.indices.map { memory.getWordNoNotify(memory.dataBaseAddress + it * 4) })
        readAction {
            assertEquals(literals, descendants(assertParses(source).node, "LITERAL_EXPR").map { it.text }.toList())
        }
    }

    @Test
    fun `every octal byte character parses and assembles directly and through a macro`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val literals = (0..255).map { "'\\${it.toString(8).padStart(3, '0')}'" }
            val source = ".data\n.word ${literals.joinToString(", ")}\n" +
                ".macro emit (%value)\n.word %value\n.end_macro\n" +
                literals.joinToString("\n", postfix = "\n.text\nnop\n") { "emit($it)" }
            assertAssembles(directory, source, 0)
            val memory = project.get().service<MarsSessionService>().context.memory
            for (index in 0 until 512) {
                assertEquals(index % 256, memory.getWordNoNotify(memory.dataBaseAddress + index * 4), "word $index")
            }
            readAction {
                val file = assertParses(source)
                assertEquals(literals, descendants(file.node, "LITERAL_EXPR").map { it.text }.toList())
                assertEquals(literals + literals, descendants(file.node, "CHARACTER").map { it.text }.toList())
            }
        }

    @Test
    fun `unfinished characters retain later operands replacement tokens and macro arguments`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = ".byte 'A, 'B', 7 # keep\n.eqv BROKEN 'C, 8\nemit('D, 9)\nnext: nop\n"
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("broken.asm", MipsFileType, source)
            assertEquals(source, file.text)
            val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            assertEquals(listOf("'A", "'C", "'D"), errors.map { it.text })
            assertTrue(errors.all { it.errorDescription == "Unterminated character literal" })
            assertEquals(listOf("'A", "'B'", "7"), descendants(file.node, "OPERAND").map { it.text }.toList())
            assertEquals(listOf("'C", "8"), descendants(file.node, "REPLACEMENT").map { it.text }.toList())
            assertEquals(listOf("(", "'D", "9", ")"), descendants(file.node, "INVOCATION_TOKEN").map { it.text }.toList())
            assertEquals(listOf("# keep"), descendants(file.node, "COMMENT").map { it.text }.toList())
            assertEquals("nop", descendants(file.node, "INSTRUCTION").single().text)
        }
    }

    @Test
    fun `closed invalid characters are errors without consuming later source tokens`() = timeoutRunBlocking<Unit> {
        readAction {
            val source = ".byte 'ab', 7, '\\q', 8\n.eqv BROKEN '\\400', 9\nemit('', '\\,', 10)\nnext: nop\n"
            val file = PsiFileFactory.getInstance(project.get()).createFileFromText("invalid.asm", MipsFileType, source)
            assertEquals(source, file.text)
            val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            assertEquals(listOf("'ab'", "'\\q'", "'\\400'", "''", "'\\,'"), errors.map { it.text })
            assertTrue(errors.all { it.errorDescription == "Invalid MARS character literal" })
            assertEquals(listOf("'ab'", "7", "'\\q'", "8"), descendants(file.node, "OPERAND").map { it.text }.toList())
            assertEquals(listOf("'\\400'", "9"), descendants(file.node, "REPLACEMENT").map { it.text }.toList())
            assertEquals(listOf("(", "''", "'\\,'", "10", ")"),
                descendants(file.node, "INVOCATION_TOKEN").map { it.text }.toList())
            assertEquals("next:", descendants(file.node, "LABEL").single().text)
            assertArrayEquals(arrayOf(invalidColor),
                MipsSyntaxHighlighter().getTokenHighlights(MipsTypes.psi("INVALID_CHARACTER")))
        }
    }

    @Test
    fun `double-quoted data follows assembler quote boundaries`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val first = "\"A" + "\\\\" + "\"#B\""
        val second = "\"C" + "\\\\" + "\""
        val source = ".data\n.asciiz $first\n.asciiz $second\n.text\nnop\n"
        assertAssembles(directory, source, expectedInstruction = 0)
        val memory = project.get().service<MarsSessionService>().context.memory
        val expected = "A\\\"#B\u0000C\\\u0000"
        assertEquals(expected.map(Char::code), expected.indices.map { memory.getByte(memory.dataBaseAddress + it) })
        readAction {
            val strings = SyntaxTraverser.astTraverser(assertParses(source).node)
                .filterTypes { it == MipsTypes.psi("STRING") }.map { it.text }.toList()
            assertEquals(listOf(first, second), strings)
        }
    }

    @Test
    fun `unfinished quotes retain raw operands accepted by the assembler`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            directory.resolve("part.s").writeText("addiu \$t0, \$zero, 7\n")
            val source = """
                .eqv RAW "alias
                .set RAW
                .set "ignored
                .text "suffix
                .macro unused
                .asciiz "inert
                .end_macro
                .macro swallow (%arg)
                .set %arg
                .end_macro
                swallow "argument
                .include "part.s" "discarded
            """.trimIndent()
            assertAssembles(directory, source)
            readAction {
                val file = assertParses(source)
                val unclosed = descendants(file.node, "UNCLOSED_STRING").map { it.text }.toList()
                assertEquals(listOf("\"alias", "\"ignored", "\"suffix", "\"inert", "\"argument", "\"discarded"), unclosed)
                assertEquals("\"alias", descendants(file.node, "REPLACEMENT").single().text)
                assertEquals("\"argument", descendants(file.node, "INVOCATION_TOKEN").single().text)
                assertEquals(".include \"part.s\" \"discarded", descendants(file.node, "INCLUDE_LINE").single().text)
                assertEquals("\"inert", descendants(file.node, "LITERAL_EXPR").single().text)
                val highlighter = MipsSyntaxHighlighter()
                assertArrayEquals(
                    highlighter.getTokenHighlights(MipsTypes.psi("STRING")),
                    highlighter.getTokenHighlights(MipsTypes.psi("UNCLOSED_STRING")),
                )
                val debugger = MipsLexer(debuggerExpression = true)
                debugger.start(" \"incomplete")
                debugger.advance()
                assertEquals(0, debugger.getState())
                assertEquals(MipsTypes.syntax.getValue("BAD_CHARACTER"), debugger.getTokenType())
            }
        }

    @Test
    fun `ignored set payloads retain assembler tokens without operand errors`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val context = project.get().service<MarsSessionService>().context
        for (directive in listOf(".SeT ), :, (, +", ".set", ".set noat", ".set: nop", ".eqv OPTION .set\nOPTION ), :")) {
            val source = "$directive # ignored by the assembler\nentry: nop\n"
            val path = directory.resolve("set.asm").also { it.writeText(source) }
            val program = context.assemble(listOf(path), null, true, false)
            assertEquals(listOf(0), program.machineList.map { it.binaryStatement })
            val warning = program.assemblyDiagnostics.errorMessages.single()
            assertTrue(warning.isWarning)
            assertTrue(warning.message.contains("ignores the .set directive"))
            readAction {
                val file = assertParses(source)
                assertEquals(listOf("entry:"), descendants(file.node, "LABEL").map { it.text }.toList())
                assertEquals("nop", descendants(file.node, "INSTRUCTION").single().text)
            }
        }
        // Lexical coloring and tolerant name parsing are also preserved for the specialized token.
        readAction {
            assertParses(".set: nop\nb .set\n")
            assertArrayEquals(
                arrayOf(directiveColor),
                MipsSyntaxHighlighter().getTokenHighlights(MipsTypes.psi("SET")),
            )
        }
    }

    @Test
    fun `segment switches use one address and accept assembler-ignored suffixes`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv USER_DATA 0x10010020
            .DaTa USER_DATA, ), :
            .word 11
            .kdata 0x90000020 ) ignored
            .word 22
            .ktext 0x80000020 : (
            nop
            .text 0x00400020 )
            nop
        """.trimIndent() + "\n"
        val context = project.get().service<MarsSessionService>().context
        val path = directory.resolve("segments.asm").also { it.writeText(source) }
        val program = context.assemble(listOf(path), null, true, false)
        val memory = context.memory
        assertEquals(
            listOf(memory.textBaseAddress + 32, memory.kernelTextBaseAddress + 32),
            program.machineList.map { it.address },
        )
        assertEquals(11, memory.getWordNoNotify(memory.dataBaseAddress + 32))
        assertEquals(22, memory.getWordNoNotify(memory.kernelDataBaseAddress + 32))
        readAction {
            val file = assertParses(source)
            val directives = descendants(file.node, "DIRECTIVE_STATEMENT")
                .filter { it.firstChildNode.elementType == MipsTypes.psi("SEGMENT") }
            assertEquals(
                listOf(".DaTa USER_DATA, ), :", ".kdata 0x90000020 ) ignored", ".ktext 0x80000020 : (", ".text 0x00400020 )"),
                directives.map { it.text }.toList(),
            )
            assertEquals(listOf("USER_DATA"), descendants(file.node, "SEGMENT_ADDRESS_SYMBOL").map { it.text }.toList())
            assertArrayEquals(
                arrayOf(directiveColor),
                MipsSyntaxHighlighter().getTokenHighlights(MipsTypes.psi("SEGMENT")),
            )
        }
    }

    @Test
    fun `every core directive parses and assembles in ordinary and comma-heavy source`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv WORD_VALUE 7
            .macro emit (%x)
              addiu ${'$'}t0, ${'$'}zero, %x
            .end_macro
            .include "data.s"
            .extern externalWord 4
            .kdata
            kernelData: .word 99
            .ktext
            kernelEntry: nop
            .text
            .globl entry
            .set noat
            entry: emit(WORD_VALUE)
        """.trimIndent()
        val data = """
            .data
            .ascii "A", "B"
            .asciiz "C"
            .byte 3
            .align 1
            .half -6
            .space 4
            .align 2
            .word WORD_VALUE
            .float 1.5
            .double 2.25
        """.trimIndent()
        fun withSeparators(text: String) = text.lineSequence().joinToString("\n") { ",,${it.replaceFirst(" ", ",,")},," }
        val context = project.get().service<MarsSessionService>().context
        for ((entry, included) in listOf(source to data, withSeparators(source) to withSeparators(data))) {
            directory.resolve("data.s").writeText(included)
            val file = directory.resolve("entry.asm").also { it.writeText(entry) }
            val program = context.assemble(listOf(file), null, true, false)
            val memory = context.memory
            val base = memory.dataBaseAddress
            assertEquals(listOf(memory.textBaseAddress, memory.kernelTextBaseAddress), program.machineList.map { it.address })
            assertEquals(listOf(0x24080007, 0), program.machineList.map { it.binaryStatement })
            assertEquals(memory.externBaseAddress, context.symbolTable.getAddress("externalWord"))
            assertEquals(memory.textBaseAddress, context.symbolTable.getAddress("entry"))
            assertEquals(99, memory.getWordNoNotify(memory.kernelDataBaseAddress))
            assertEquals(listOf(65, 66, 67, 0, 3), (0..4).map { memory.getByte(base + it) })
            assertEquals(0xfffa, memory.getHalf(base + 6))
            assertEquals(0, memory.getWordNoNotify(base + 8))
            assertEquals(7, memory.getWordNoNotify(base + 12))
            assertEquals(1.5f.toBits(), memory.getWordNoNotify(base + 16))
            assertEquals((2.25.toBits() ushr 32).toInt(), memory.getWordNoNotify(base + 28))
            assertTrue(program.assemblyDiagnostics.errorMessages.single().isWarning)
            assertTrue(program.assemblyDiagnostics.errorMessages.single().message.contains(".set"))

            readAction {
                val directiveTypes = listOf("DIRECTIVE", "EQV", "MACRO", "END_MACRO", "INCLUDE", "SET", "SEGMENT", "EXTERN").map(MipsTypes::psi).toSet()
                val parsed = listOf(entry, included).flatMap { text ->
                    SyntaxTraverser.astTraverser(assertParses(text).node).filterTypes { it in directiveTypes }.map { it.text }.toList()
                }.toSet()
                assertEquals(Directives.getDirectiveList().map { it.name }.toSet(), parsed)
            }
        }
    }

    @Test
    fun `extern recognizes names spelled like registers and directives`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val names = listOf("\$t0", ".word", ".set")
        val source = names.joinToString("\n", postfix = "\n.text\nmain: nop\n") { ".extern $it, 4" }
        val entry = directory.resolve("extern.mars").also { it.writeText(source) }
        val context = project.get().service<MarsSessionService>().context
        context.assemble(listOf(entry), null, true, false)
        val base = context.memory.externBaseAddress
        assertEquals(listOf(base, base + 4, base + 8), names.map(context.symbolTable::getAddress))

        readAction {
            val file = assertParses(source, entry.fileName.toString())
            val declarations = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)
                .filter { it.node.elementType == MipsTypes.psi("EXTERNAL_NAME") }
            assertEquals(names, declarations.map { it.name })
            assertEquals(names.map { source.indexOf(it) }, declarations.map { it.textOffset })
            for (declaration in declarations) {
                assertSame(declaration, MipsDeclaration.findAt(file, declaration.textOffset, declaration.name!!))
            }
            assertEquals("nop", descendants(file.node, "INSTRUCTION").single().text)
        }
    }

    @Test
    fun `extern retains the whole name token for punctuation literals and directive keywords`(
        @TempDir directory: Path,
    ) = timeoutRunBlocking<Unit> {
        val spellings = listOf(")", "(", ":", "+", "-", "-4", "+8", "17", "1.2e-5", "\"slot name\"", "'A'",
            ".data", ".macro", ".end_macro", ".eqv", ".extern", ".include")
        val names = spellings.map { if (it == "'A'") "65" else it }
        val prefix = ".extern ,,"
        val lines = spellings.map { "$prefix$it,,4\n" }
        val source = lines.joinToString("") + ".text\nmain: nop\n"
        val entry = directory.resolve("extern.mars").also { it.writeText(source) }
        val context = project.get().service<MarsSessionService>().context
        context.assemble(listOf(entry), null, true, false)
        val base = context.memory.externBaseAddress
        assertEquals(names.indices.map { base + 4 * it }, names.map(context.symbolTable::getAddress))

        readAction {
            val file = assertParses(source, entry.fileName.toString())
            val declarations = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)
                .filter { it.node.elementType == MipsTypes.psi("EXTERNAL_NAME") }
            assertEquals(spellings, declarations.map { it.nameIdentifier?.text })
            assertEquals(names, declarations.map { it.name })
            val offsets = lines.runningFold(0) { offset, line -> offset + line.length }.dropLast(1)
                .map { it + prefix.length }
            assertEquals(offsets, declarations.map { it.textOffset })
            for (index in names.indices) {
                assertSame(declarations[index], MipsDeclaration.findAt(file, offsets[index], names[index]))
            }
            assertNull(MipsDeclaration.findAt(file, offsets[5] + 1, names[5]))
            assertEquals("nop", descendants(file.node, "INSTRUCTION").single().text)
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
                .byte 'a', '\n', '\123'
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
    fun `lexer preserves mnemonic tokens for contextual highlighting`() {
        val highlighter = MipsSyntaxHighlighter()
        val lexer = highlighter.highlightingLexer
        for (mnemonic in listOf("addiu", "LI", "c.eq.s")) {
            lexer.start(mnemonic)
            assertSame(MipsTypes.psi("MNEMONIC"), lexer.tokenType)
            assertArrayEquals(
                arrayOf(DefaultLanguageHighlighterColors.IDENTIFIER),
                highlighter.getTokenHighlights(lexer.tokenType!!),
                mnemonic,
            )
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
            for (broken in listOf("lw \$t0,4(\$sp", "add \$t0,+", "bad @ token", ".eqv @", ".eqv -0xG", ".eqv ,,, 7 8", ".macro foo (1", ".macro 7",
                ".word 1 != 2", ".word 1 <= 2", ".word (1+2)",
                ".word 1 | 2", ".word 1 ^ 2", ".word 1 & 2", ".word ~1", ".word 1 << 2", ".word 1 >>> 2")) {
                val text = broken + "\nnext: nop\n"
                val file = PsiFileFactory.getInstance(project.get()).createFileFromText("broken.asm", MipsFileType, text)
                assertEquals(text, file.text)
                assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isNotEmpty(), broken)
                assertEquals("nop", descendants(file.node, "INSTRUCTION").last().text)
            }
        }
    }

    @Test
    fun `every truncation preserves source and recovers the next instruction`() = timeoutRunBlocking<Unit> {
        readAction {
            val factory = PsiFileFactory.getInstance(project.get())
            val sources = listOf(
                ".macro m (%x)\nlocal: lw \$t0,label+4(\$sp) #note\n.asciiz \"hello\"\n.end_macro\n.data\n.word 1:4\n.text\nm(5)\n",
                ".eqv MIN -0x080000000\n.macro pair(\$dst, %value)\nli \$dst, %value\n.end_macro\npair(\$t1, MIN)\n",
                ": .include \"folder/part.s\" +\n.extern shared,4\n.kdata\n.word shared\n -3:2\n.set ignored )\n",
            )
            for (source in sources) {
                for (end in 0..source.length) {
                    val prefix = source.take(end)
                    val eof = factory.createFileFromText("prefix.asm", MipsFileType, prefix)
                    assertEquals(prefix, eof.node.text)

                    val text = "$prefix\nrecovery_anchor: nop\n"
                    val file = factory.createFileFromText("prefix.asm", MipsFileType, text)
                    assertEquals(text, file.node.text)
                    assertEquals("recovery_anchor:", descendants(file.node, "LABEL").last().text, text)
                    assertEquals("nop", descendants(file.node, "INSTRUCTION").last().text, text)
                }
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
    fun `binary workbench and its included library parse and assemble`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val sources = listOf("workbench.asm", "bit_algorithms.s").associateWith { name ->
            checkNotNull(javaClass.getResourceAsStream("/smoke/instruction-lab/src/main/mips/$name"))
                .bufferedReader().use { it.readText() }
        }
        readAction {
            val workbench = assertParses(sources.getValue("workbench.asm"))
            assertEquals(3, descendants(workbench.node, "MACRO_DEFINITION").count())
            assertParses(sources.getValue("bit_algorithms.s"))
        }
        sources.forEach { (name, source) -> directory.resolve(name).writeText(source) }
        val program = project.get().service<MarsSessionService>().context
            .assemble(listOf(directory.resolve("workbench.asm")), null, true, false)
        assertTrue(program.instructionCount() > 0)
        assertTrue(program.sourceMap().origins().values.any { it.location().file().fileName.toString() == "bit_algorithms.s" })
    }

    @Test
    fun `PNG decoder parses and preserves machine code and origins with MARS and include suffixes`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val originals = listOf("viewer.asm", "png.s", "deflate.s").associateWith { name ->
                checkNotNull(javaClass.getResourceAsStream("/smoke/png-decoder/src/main/mips/$name"))
                    .bufferedReader().use { it.readText() }
            }
            val renamed = linkedMapOf(
                "viewer.mars" to originals.getValue("viewer.asm")
                    .replace("\"png.s\"", "\"png.inc\"")
                    .replace("\"deflate.s\"", "\"deflate.inc\""),
                "png.inc" to originals.getValue("png.s"),
                "deflate.inc" to originals.getValue("deflate.s"),
            )
            readAction {
                for ((name, text) in originals + renamed) {
                    val file = assertParses(text, name)
                    assertTrue(descendants(file.node, "INSTRUCTION").any(), name)
                }
            }
            for ((name, text) in originals + renamed) directory.resolve(name).writeText(text)
            val context = project.get().service<MarsSessionService>().context
            val originalProgram = context.assemble(listOf(directory.resolve("viewer.asm")), null, true, false)
            val expected = originalProgram.machineList.map { it.address to it.binaryStatement }
            assertTrue(expected.size > 500, "The full decoder and both included libraries must assemble")
            val renamedProgram = context.assemble(listOf(directory.resolve("viewer.mars")), null, true, false)
            assertEquals(expected, renamedProgram.machineList.map { it.address to it.binaryStatement })
            val origins = renamedProgram.sourceMap().origins().values.map { it.location() }
            assertEquals(renamed.keys, origins.map { it.file().fileName.toString() }.toSet())
            for (name in renamed.keys) {
                assertTrue(origins.count { it.file().fileName.toString() == name } > 30, name)
            }
        }

    @Test
    fun `unsigned address pseudo examples select their advertised expansion`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val catalog = InstructionSet().also { it.populate() }.instructionList
            val basicNames = catalog.filterIsInstance<BasicInstruction>().map { it.name }.toSet()
            val examples = catalog.filterIsInstance<ExtendedInstruction>().filter {
                it.name in basicNames && it.tokenList.size() == 6 &&
                    it.tokenList[2].type == TokenTypes.INTEGER_16U && it.tokenList[3].type == TokenTypes.LEFT_PAREN
            }
            assertEquals(14, examples.size)
            val offsets = linkedMapOf("32767" to listOf<String>(), "32768" to listOf("ori", "addu"),
                "65535" to listOf("ori", "addu"), "65536" to listOf("lui", "addu"),
                "0x8000" to listOf("ori", "addu"), "0100000" to listOf("ori", "addu"),
                "+32768" to listOf("ori", "addu"))
            val cases = examples.flatMap { form ->
                val example = form.exampleFormat
                val offsetToken = form.tokenList[2]
                listOf(example to listOf("ori", "addu", form.name)) + offsets.map { (offset, prefix) ->
                    example.replaceRange(offsetToken.startPos - 1, offsetToken.endPos - 1, offset) to (prefix + form.name)
                }
            }
            val source = cases.joinToString("\n", prefix = ".text\n", postfix = "\n") { it.first }
            val entry = directory.resolve("address-offsets.asm").also { it.writeText(source) }
            val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
            val sourceMap = program.sourceMap()
            val byLine = program.machineList.groupBy { sourceMap.origins().getValue(it.address).location().line() }
            assertEquals((2..cases.size + 1).toSet(), byLine.keys)
            for ((index, case) in cases.withIndex()) {
                val (written, expansion) = case
                val statements = byLine.getValue(index + 2)
                assertEquals(expansion, statements.map { it.instruction.name }, written)
                assertEquals(List(expansion.size) { expansion.size > 1 },
                    statements.map { it.address in sourceMap.pseudoAddresses() }, written)
            }
            readAction {
                val operations = descendants(assertParses(source).node, "INSTRUCTION").toList()
                assertEquals(cases.map { it.first }, operations.map { it.text })
                operations.forEach { operation ->
                    assertEquals(listOf("\$t1", operation.text.substringAfter(',')),
                        descendants(operation, "OPERAND").map { it.text }.toList(), operation.text)
                }
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
            "lw \$t0, (\$sp)\n.word (1 + 2) - 3\n.word +(-2)\n",
            ".data\n.globl main\n.extern name,4\n.align 2\n.space 8\n.half 0xffff\n.ascii \"x\"\n",
            ".kdata 0x90000000\n.ktext 0x80000180\n.set noat\n.include \"other.asm\"\n",
        )
        readAction { for (text in cases) assertParses(text) }
    }

    private fun assertParses(source: String, name: String = "example.asm"): MipsFile {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
        val file = PsiFileFactory.getInstance(project.get()).createFileFromText(name, fileType, source)
        val mips = assertInstanceOf(MipsFile::class.java, file)
        assertSame(MipsFileType, file.fileType)
        assertEquals(source, file.text)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        assertTrue(errors.isEmpty(), source + "\n" + errors.joinToString { it.errorDescription + " at " + it.textRange })
        return mips
    }
}
