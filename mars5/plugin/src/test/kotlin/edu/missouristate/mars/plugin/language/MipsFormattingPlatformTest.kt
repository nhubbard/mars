package edu.missouristate.mars.plugin.language

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.Indent
import com.intellij.lang.LanguageFormatting
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsFormattingPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `reformat normalizes native syntax and preserves machine words and data`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
              .eqv    OFFSET -4
             .eqv OP   addiu  ${'$'}t0,   ${'$'}t0,  1
            .eqv DEF .eqv
            DEF AGAIN 1,   2
            .eqv AGAIN 1,   2
               .include   "macros.s"

                .macro   local  ( %reg  , %value )
            addiu  %reg , %reg , -1
             addi   %reg, %reg, %value
               .end_macro
               .data
             words  :    .word  4 ,  -2, AGAIN
            message: .asciiz  "x  y,  (-1)"
            repeated: .word 7  :  2
               .text
             main   :
             lw  ${'$'}t0 ,  -4  ( ${'$'}sp )
             lw  ${'$'}t1 , OFFSET  ( ${'$'}sp )
                 OP
             local ( ${'$'}t0 , 2 )
                  emit( ${'$'}t0  ,  7 )    # invocation may also be a text substitution
            target :  nop
             j   target
        """.trimIndent()
        val formatted = withContext(Dispatchers.EDT) {
            fixture.configureByText("layout.asm", source)
            val first = reformat()
            assertEquals(first, reformat(), "formatting must be idempotent")
            first
        }
        val expected = """
            .eqv OFFSET -4
            .eqv OP   addiu  ${'$'}t0,   ${'$'}t0,  1
            .eqv DEF .eqv
            DEF AGAIN 1,   2
            .eqv AGAIN 1,   2
            .include   "macros.s"

            .macro local (%reg, %value)
                addiu %reg, %reg, -1
                addi %reg, %reg, %value
            .end_macro
            .data
            words: .word 4, -2, AGAIN
            message: .asciiz "x  y,  (-1)"
            repeated: .word 7:2
            .text
            main:
                lw ${'$'}t0, -4(${'$'}sp)
                lw ${'$'}t1, OFFSET(${'$'}sp)
                OP
                local (${'$'}t0, 2)
                emit( ${'$'}t0  ,  7 ) # invocation may also be a text substitution
            target: nop
                j target
        """.trimIndent()
        assertEquals(expected, formatted)
        directory.resolve("macros.s").writeText(".macro emit (%reg, %value)\naddiu %reg, %reg, %value\n.end_macro\n")
        assertEquals(assemble(directory.resolve("before.asm"), source), assemble(directory.resolve("after.asm"), formatted))
    }

    @Test
    fun `reformat uses the substituted operation at alias call sites without changing assembly`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv LOAD lw
            .eqv CALL emit
            .eqv OFFSET -4
            .eqv BASE ${'$'}sp
            .eqv CLOSE )
            .eqv DEFINE .eqv
            .eqv MARKER alias_label:
            DEFINE KEEP 1,   2
            .macro emit (%value)
                li ${'$'}t1, %value
            .end_macro
            .macro discard (%a, %b, %c, %d, %e)
                nop
            .end_macro
            .text
            MARKER
            LOAD  ${'$'}t0 , OFFSET  ( BASE )
            LOAD  ${'$'}t2 , ( BASE )
            CALL( OFFSET )
            CALL ( OFFSET CLOSE
            discard ignored .eqv RAW 1,   2
            .eqv RAW 1,   2
        """.trimIndent()
        val formatted = withContext(Dispatchers.EDT) {
            fixture.configureByText("alias-layout.asm", source)
            DumbModeTestUtils.runInDumbModeSynchronously(fixture.project) {
                assertTrue("LOAD  ${'$'}t0 , OFFSET  ( BASE )" in reformat())
            }
            val result = reformat()
            assertEquals(result, reformat())
            result
        }
        assertTrue("    LOAD ${'$'}t0, OFFSET(BASE)" in formatted, formatted)
        assertTrue("    LOAD ${'$'}t2, (BASE)" in formatted, formatted)
        assertTrue("    CALL(OFFSET)" in formatted, formatted)
        assertTrue("    CALL (OFFSET CLOSE" in formatted, formatted)
        assertTrue("DEFINE KEEP 1,   2" in formatted, formatted)
        assertTrue("discard ignored .eqv RAW 1,   2" in formatted, formatted)
        assertEquals(assemble(directory.resolve("alias-before.asm"), source),
            assemble(directory.resolve("alias-after.asm"), formatted))
    }

    @Test
    fun `aliases that complete an operand expose its safe spacing boundaries`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv OPEN (
            .eqv CLOSE )
            .eqv BASE ${'$'}sp
            .eqv OFFSET -4
            .eqv DEST ${'$'}t0
            .eqv PREFIX 4(
            .eqv PAIR 7,   9
            .data
            values: .word PAIR
            .text
            lw  DEST , OFFSET  ( BASE CLOSE
            lw  ${'$'}t1  , PREFIX   BASE  )
            lw  ${'$'}t2 , OFFSET OPEN  BASE )
            lw  DEST  ( BASE CLOSE
        """.trimIndent()
        val formatted = withContext(Dispatchers.EDT) {
            fixture.configureByText("compound-addresses.asm", source)
            val result = reformat()
            assertEquals(result, reformat())
            result
        }
        assertEquals("""
            .eqv OPEN (
            .eqv CLOSE )
            .eqv BASE ${'$'}sp
            .eqv OFFSET -4
            .eqv DEST ${'$'}t0
            .eqv PREFIX 4(
            .eqv PAIR 7,   9
            .data
            values: .word PAIR
            .text
                lw DEST, OFFSET(BASE CLOSE
                lw ${'$'}t1, PREFIX BASE)
                lw ${'$'}t2, OFFSET OPEN BASE)
                lw DEST (BASE CLOSE
        """.trimIndent(), formatted)
        assertEquals(assemble(directory.resolve("compound-before.asm"), source),
            assemble(directory.resolve("compound-after.asm"), formatted))
    }

    @Test
    fun `complete operands use expanded address and group roles when spacing parentheses`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv LOAD lw
            .eqv DEST ${'$'}t0
            .eqv BASE ${'$'}sp
            .eqv PAIR ${'$'}t2, 8
            .eqv LABEL entry:
            .text
            lw  DEST( BASE )
            LOAD  DEST( BASE )
            LABEL lw DEST( BASE )
            lw  PAIR   ( BASE )
            LOAD PAIR   ( BASE )
            lw ${'$'}t1, 4   ( ${'$'}sp )
        """.trimIndent()
        val formatted = withContext(Dispatchers.EDT) {
            fixture.configureByText("complete-alias-addresses.asm", source)
            val result = reformat()
            assertEquals(result, reformat())
            result
        }
        assertEquals("""
            .eqv LOAD lw
            .eqv DEST ${'$'}t0
            .eqv BASE ${'$'}sp
            .eqv PAIR ${'$'}t2, 8
            .eqv LABEL entry:
            .text
                lw DEST (BASE)
                LOAD DEST (BASE)
            LABEL lw DEST (BASE)
                lw PAIR(BASE)
                LOAD PAIR(BASE)
                lw ${'$'}t1, 4(${'$'}sp)
        """.trimIndent(), formatted)
        assertEquals(assemble(directory.resolve("complete-before.asm"), source),
            assemble(directory.resolve("complete-after.asm"), formatted))
    }

    @Test
    fun `operand aliases that leave invalid or incomplete syntax keep their internal spacing`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val source = """
                .eqv OFFSET 4
                .eqv LOAD lw
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                .text
                lw ${'$'}t0 , OFFSET  ( ${'$'}sp
                lw ${'$'}t1 , FIRST  ( ${'$'}sp
                lw ${'$'}t2 , UNKNOWN  ( ${'$'}sp
                LOAD ${'$'}t3, OFFSET  ( ${'$'}sp
                .word OFFSET    )
                next: nop
            """.trimIndent()
            fixture.configureByText("incomplete-addresses.asm", source)
            val result = reformat()
            assertTrue("${'$'}t0 , OFFSET  ( ${'$'}sp" in result, result)
            assertTrue("${'$'}t1 , FIRST  ( ${'$'}sp" in result, result)
            assertTrue("${'$'}t2 , UNKNOWN  ( ${'$'}sp" in result, result)
            assertTrue("LOAD ${'$'}t3, OFFSET (${'$'}sp" in result, result)
            assertTrue(".word OFFSET    )" in result, result)
            assertTrue("next: nop" in result, result)
            assertEquals(result, reformat())
        }
    }

    @Test
    fun `expanded labels and directives set the margin without rewriting aliases`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv DATA .data
            .eqv TEXT .text
            .eqv SEP :
            .eqv MARKER target:
            .eqv VALUE 7
               DATA
            number   SEP    .word  VALUE
                TEXT
               MARKER   li  ${'$'}t0 ,  VALUE
               entry    SEP   nop
                addiu   SEP   nop
              j   target
        """.trimIndent()
        val formatted = withContext(Dispatchers.EDT) {
            fixture.configureByText("alias-margins.asm", source)
            val result = reformat()
            assertEquals(result, reformat())
            result
        }
        assertEquals("""
            .eqv DATA .data
            .eqv TEXT .text
            .eqv SEP :
            .eqv MARKER target:
            .eqv VALUE 7
            DATA
            number SEP .word VALUE
            TEXT
            MARKER li ${'$'}t0, VALUE
            entry SEP nop
            addiu SEP nop
                j target
        """.trimIndent(), formatted)
        assertEquals(assemble(directory.resolve("margins-before.asm"), source),
            assemble(directory.resolve("margins-after.asm"), formatted))
    }

    @Test
    fun `macro indentation follows effective boundaries inside and outside physical macro blocks`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv OPEN .macro
            .eqv CLOSE .end_macro
            .text
              OPEN first (%arg)
            local: addiu ${'$'}t0, ${'$'}zero, %arg
            .eqv VALUE 6
                 CLOSE # closes the aliased opener
                outside: nop
              .macro second
            inside: nop
                 CLOSE # closes the written opener
                next: nop
              .macro third
            third_label: nop
                 .end_macro
            first(7)
            second
            third
        """.trimIndent()
        val formatted = withContext(Dispatchers.EDT) {
            fixture.configureByText("macro-boundaries.asm", source)
            val result = reformat()
            assertEquals(result, reformat())
            result
        }
        assertEquals("""
            .eqv OPEN .macro
            .eqv CLOSE .end_macro
            .text
            OPEN first (%arg)
                local: addiu ${'$'}t0, ${'$'}zero, %arg
                .eqv VALUE 6
            CLOSE # closes the aliased opener
            outside: nop
            .macro second
                inside: nop
            CLOSE # closes the written opener
            next: nop
            .macro third
                third_label: nop
            .end_macro
                first(7)
                second
                third
        """.trimIndent(), formatted)
        assertEquals(assemble(directory.resolve("boundaries-before.asm"), source),
            assemble(directory.resolve("boundaries-after.asm"), formatted))
    }

    @Test
    fun `an included body takes its indentation from the enclosing entry macro`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val entry = ".eqv OPEN .macro\n.eqv STOP .end_macro\n.text\nOPEN emit\n.include \"macro-body.s\"\n    emit\n"
        val body = "inside :   nop\nsecond :  nop\n    STOP\n    outside :   nop\n  nop\n"
        val current = project.get()
        val parent = withContext(Dispatchers.EDT) {
            val parent = fixture.addFileToProject("entry.asm", entry)
            val part = fixture.addFileToProject("macro-body.s", body)
            fixture.configureFromExistingVirtualFile(part.virtualFile)
            parent.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val (formattedBody, formattedEntry) = withContext(Dispatchers.EDT) {
            val part = reformat()
            assertEquals(part, reformat())
            fixture.configureFromExistingVirtualFile(parent)
            val parent = reformat()
            assertEquals(parent, reformat())
            part to parent
        }
        assertEquals("    inside: nop\n    second: nop\nSTOP\noutside: nop\n    nop\n", formattedBody)
        assertEquals(entry.replace("\n.include", "\n    .include"), formattedEntry)
        val include = directory.resolve("macro-body.s")
        include.writeText(body)
        val before = assemble(directory.resolve("include-before.asm"), entry)
        include.writeText(formattedBody)
        assertEquals(before, assemble(directory.resolve("include-after.asm"), formattedEntry))
    }

    @Test
    fun `an invalid macro name retains physical indentation while editing`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.configureByText("invalid-macro.asm", ".macro nop\nlabel :    nop\n.end_macro\nnext: nop\n")
            assertEquals(".macro nop\n    label: nop\n.end_macro\nnext: nop\n", reformat())
        }
    }

    @Test
    fun `an incomplete aliased closer keeps the macro body indented`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val header = ".eqv OPEN .macro\n.eqv STOP .end_macro\nOPEN emit\n"
            fixture.configureByText("incomplete-macro.asm", header + "STOP stray\ninside: nop\n.end_macro\n    outside: nop\n")
            assertEquals(header + "    STOP stray\n    inside: nop\n.end_macro\noutside: nop\n", reformat())
        }
    }

    @Test
    fun `selection formatting respects project indent size and preserves malformed lines`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            val source = ".text\nstart :   nop\n   lw ${'$'}t0,4(${'$'}sp\n  addiu ${'$'}t0 , ${'$'}zero , 1\nnext :    nop\n"
            val settings = CodeStyle.createTestSettings(CodeStyle.getSettings(current))
            settings.getCommonSettings(MipsLanguage).indentOptions!!.INDENT_SIZE = 2
            settings.AUTODETECT_INDENTS = false
            CodeStyle.doWithTemporarySettings(current, settings, Runnable {
                fixture.configureByText("selection.s", source)
                assertEquals(2, CodeStyle.getIndentOptions(fixture.file).INDENT_SIZE)
                val start = source.indexOf("  addiu")
                val end = source.indexOf("next")
                WriteCommandAction.runWriteCommandAction(current) {
                    CodeStyleManager.getInstance(current).reformatText(fixture.file, listOf(TextRange(start, end)))
                }
                assertEquals(
                    ".text\nstart :   nop\n   lw ${'$'}t0,4(${'$'}sp\n  addiu ${'$'}t0, ${'$'}zero, 1\nnext :    nop\n",
                    fixture.editor.document.text,
                )
            })
        }
    }

    @Test
    fun `native code style settings expose a formatting preview and indentation options`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            val provider = requireNotNull(LanguageCodeStyleSettingsProvider.forLanguage(MipsLanguage))
            assertTrue(requireNotNull(provider.getCodeSample(LanguageCodeStyleSettingsProvider.SettingsType.INDENT_SETTINGS)).contains(".macro"))
            val base = CodeStyle.getSettings(current)
            assertEquals(4, base.getCommonSettings(MipsLanguage).indentOptions!!.INDENT_SIZE)
            val configurable = provider.createConfigurable(base, CodeStyle.createTestSettings(base))
            try {
                checkNotNull(configurable.createComponent())
            } finally {
                configurable.disposeUIResources()
            }
        }
    }

    @Test
    fun `formatting preserves unjoined signs and can recover around broken syntax`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val source = """
                .data
                message: .asciiz"x  y"
                .word 1e-2, 1-2+3
                .text
                entry   :   la ${'$'}t0 , message+4
                malformed: )  6  (
                unknown 'X,   7
                lw ${'$'}t0 , 4  ( ${'$'}sp
                lw ${'$'}t1  ,  0(${'$'}sp)
                lw ${'$'}t2   ,  4(${'$'}sp)
                addiu ${'$'}t0, ${'$'}t0, - 4
                next :   nop
            """.trimIndent()
            fixture.configureByText("recovery.asm", source)
            val result = reformat()
            assertTrue("message: .asciiz\"x  y\"" in result, result)
            assertTrue("1e-2" in result, result)
            assertTrue("1-2+3" in result, result)
            assertTrue("entry: la ${'$'}t0, message+4" in result, result)
            assertTrue("malformed: )  6  (" in result, result)
            assertTrue("unknown 'X,   7" in result, result)
            assertTrue("${'$'}t1  " in result, result)
            assertTrue("${'$'}t2   ," in result, result)
            assertTrue("- 4" in result, result)
            assertTrue("next: nop" in result, result)
            assertEquals(result, reformat())
            val file = fixture.file
            val builder = requireNotNull(LanguageFormatting.INSTANCE.forContext(file))
            val root = builder.createModel(FormattingContext.create(file, CodeStyle.getSettings(file))).rootBlock
            assertEquals(Indent.Type.NORMAL, root.getChildAttributes(root.subBlocks.size).childIndent!!.type)
            assertEquals(Indent.Type.NONE, root.subBlocks.first().getChildAttributes(1).childIndent!!.type)
        }
    }

    private fun reformat(): String {
        val file = fixture.file
        WriteCommandAction.runWriteCommandAction(file.project) {
            CodeStyleManager.getInstance(file.project).reformat(file)
        }
        return fixture.editor.document.text
    }

    private fun assemble(path: Path, source: String): Pair<List<Int>, List<Int>> {
        path.writeText(source)
        return MarsSessionService.createMarsComponent().context().use { context ->
            val program = context.assemble(listOf(path), null, true, false)
            val memory = context.memory
            program.machineList.map { it.binaryStatement } to List(40) { memory.getByte(memory.dataBaseAddress + it) }
        }
    }
}
