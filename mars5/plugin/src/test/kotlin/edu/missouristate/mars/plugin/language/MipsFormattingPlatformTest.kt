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
