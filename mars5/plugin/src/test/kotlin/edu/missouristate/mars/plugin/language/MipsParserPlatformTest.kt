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
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem.Kind
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
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
                assertEquals(expectedErrors, PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.text })
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
            val rejected = mapOf("7)" to "7", "@)" to "@", "%)" to "%", "\"oops\" .eqv PHANTOM 7" to "\"oops\"")
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
    fun `all instruction formats agree with the assembler across separators and aliases`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val instructions = InstructionSet().also { it.populate() }.instructionList
        assertTrue(instructions.size > 155, "The basic and pseudo-instruction catalogs must both be populated")
        val tokens = instructions.map { instruction ->
            val list = instruction.tokenList
            List(list.size()) { index -> list[index] }
        }
        val labels = tokens.flatMap { row -> row.filter { it.type == TokenTypes.IDENTIFIER }.map { it.value } }.toSortedSet()
        val header = listOf(".text") + labels.map { "$it:" }
        val examples = instructions.map { it.exampleFormat }
        val commaHeavy = tokens.map { row ->
            val fields = listOf(row.first().value.uppercase()) + row.drop(1).map { it.value }
            fields.joinToString(",\t,,", prefix = "\t,,", postfix = ",, # assembler ignores repeated separators")
        }
        val aliasDefinitions = tokens.mapIndexed { index, row -> ".eqv OP_$index ${row.first().value}" }
        val aliasCalls = tokens.mapIndexed { index, row ->
            row.drop(1).joinToString(",\t,,", prefix = "OP_$index,\t,,") { it.value }
        }
        val macroDefinitions = tokens.map { it.size }.toSortedSet().flatMap { arity ->
            val parameters = (1..arity).map { "%arg$it" }
            listOf(".macro emit_$arity (${parameters.joinToString(",")})", parameters.joinToString(" "), ".end_macro")
        }
        val macroCalls = tokens.map { row ->
            row.joinToString(",", prefix = "emit_${row.size}(", postfix = ")") { it.value }
        }
        val context = project.get().service<MarsSessionService>().context
        fun assemble(name: String, prefix: List<String>, lines: List<String>): List<Pair<Int, Int>> {
            val source = (prefix + lines).joinToString("\n", postfix = "\n")
            val entry = directory.resolve("$name.asm").also { it.writeText(source) }
            val program = context.assemble(listOf(entry), null, true, false)
            val origins = program.sourceMap().origins()
            val words = program.machineList.map { statement ->
                val origin = origins.getValue(statement.address)
                val invocation = (origin.caller() ?: origin).location().line() - prefix.size
                statement.binaryStatement to invocation
            }
            assertEquals((1..lines.size).toSet(), words.map { it.second }.toSet(), name)
            return words
        }
        for (delayed in listOf(false, true)) {
            context.settings.setDelayedBranchingEnabled(delayed)
            val canonicalWords = assemble("canonical", header, examples)
            assertEquals(canonicalWords, assemble("comma-heavy", header, commaHeavy), "Separators with delay slots $delayed")
            assertEquals(canonicalWords, assemble("aliased", aliasDefinitions + header, aliasCalls), "Aliases with delay slots $delayed")
            assertEquals(canonicalWords, assemble("macros", macroDefinitions + header, macroCalls), "Macros with delay slots $delayed")
        }

        readAction {
            fun checkOperations(prefix: List<String>, lines: List<String>, kind: String, expected: List<String>) {
                val file = assertParses((prefix + lines).joinToString("\n", postfix = "\n"))
                val start = prefix.sumOf { it.length + 1 }
                val operations = descendants(file.node, kind).toList()
                    .filter { it.startOffset >= start }
                    .map { it.firstChildNode.text }
                assertEquals(
                    expected.map(String::lowercase),
                    operations.map(String::lowercase),
                    kind,
                )
            }
            val names = instructions.map { it.name }
            checkOperations(header, examples, "INSTRUCTION", names)
            checkOperations(header, commaHeavy, "INSTRUCTION", names)
            checkOperations(aliasDefinitions + header, aliasCalls, "OPERATION_CANDIDATE", instructions.indices.map { "OP_$it" })
            checkOperations(macroDefinitions + header, macroCalls, "OPERATION_CANDIDATE", tokens.map { "emit_${it.size}" })
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
            for (broken in listOf("lw \$t0,4(\$sp", "add \$t0,+", "bad @ token", ".eqv @", ".eqv ,,, 7 8", ".macro foo (1", ".macro 7",
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
