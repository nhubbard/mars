package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.MarsSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
class MipsParameterInfoPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native parameter info presents every distinct basic and pseudo form`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val hint = info("entry: DIV \$t0 \$t1, <caret>").orEmpty()
            val rows = hint.split("\n-\n")
            val forms = InstructionDefaults.get().matchOperator("div").map { it.exampleFormat }.toSet()
            assertEquals(forms, rows.map { it.replace("<b>", "").replace("</b>", "") }.toSet())
            assertEquals(forms.size, rows.size)
            assertTrue("div \$t1,\$t2,<b>\$t3</b>" in rows, hint)
            assertTrue("div \$t1,\$t2,<b>-100</b>" in rows, hint)
            assertTrue("div \$t1,\$t2" in rows, hint)
        }
    }

    @Test
    fun `current token follows separators and incomplete memory addresses`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            assertEquals("sll <b>\$t1</b>,\$t2,10", info("sll<caret>"))
            assertEquals("sll <b>\$t1</b>,\$t2,10", info("sll \$t<caret>0, \$t1, 4"))
            assertEquals("sll \$t1,<b>\$t2</b>,10", info("sll \$t0 <caret>"))
            val address = info("  ,,lw \$t0,, -4(<caret>").orEmpty()
            assertTrue("lw \$t1,-100(<b>\$t2</b>)" in address.split("\n-\n"), address)
            val symbol = info("lw \$t0, entry+<caret>").orEmpty()
            assertTrue("lw \$t1,label+<b>100000</b>" in symbol.split("\n-\n"), symbol)
            assertEquals("nop", info("nop <caret>"))
        }
    }

    @Test
    fun `parameter info stays off comments and noninstruction text`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for (source in listOf("   <caret>", "\u000c<caret>", "@<caret>", "a<caret>dd \$t0", "entry: <caret>", ".word <caret>1",
                "custom \$t0 <caret>", "add \$t0, \$t1 # <caret>comment")) {
                assertNull(info(source), source)
            }
        }
    }

    @Test
    fun `parameter info counts expanded operands and incomplete tokens in instruction aliases`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("aliases.s", """
                .eqv DEFINE .eqv
                DEFINE SHIFT SLL ${'$'}t0
                DEFINE LOAD lw ${'$'}t0
                DEFINE OFFSET -4(
                DEFINE PREFIX row:
                DEFINE SEP :
                DEFINE DIVIDE div ${'$'}t0,${'$'}t1
            """.trimIndent() + "\n")
            val include = ".include \"aliases.s\"\n"
            assertEquals("sll \$t1,<b>\$t2</b>,10", info("${include}entry: SHIFT<caret>"))
            assertEquals("sll \$t1,<b>\$t2</b>,10", info("${include}SHIFT \$<caret>"))
            assertEquals("sll \$t1,<b>\$t2</b>,10", info("${include}SHIFT bad%<caret>"))
            assertEquals("sll \$t1,\$t2,<b>10</b>", info("${include}SHIFT \$t1, <caret>"))
            assertEquals(info("lw \$t0, -4(<caret>"), info("${include}LOAD OFFSET<caret>"))
            assertEquals(info("sll \$t0, <caret>"), info("${include}PREFIX sll \$t0, <caret>"))
            assertEquals(info("sll \$t0, <caret>"), info("${include}split SEP sll \$t0, <caret>"))
            assertEquals(info("sll \$t0, <caret>"), info("${include}addiu SEP sll \$t0, <caret>"))
            assertEquals("sll \$t1,<b>\$t2</b>,10", info("${include}addiu SEP sll \$t0, bad%<caret>"))
            assertEquals(info("div \$t0,\$t1 <caret>"), info("${include}DIVIDE <caret>"))
        }
    }

    @Test
    fun `parameter info excludes unresolved and noninstruction aliases`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for (source in listOf(
                ".eqv WORDS .word\nWORDS <caret>1",
                ".eqv FIRST SECOND\n.eqv SECOND FIRST\nFIRST <caret>",
                ".eqv SHIFT sll \$t0\n.eqv FIRST SECOND\n.eqv SECOND FIRST\nSHIFT FIRST <caret>",
                ".eqv PREFIX row:\nPREFIX <caret>",
                ".eqv SEP :\naddiu SEP <caret>",
                "SHIFT <caret>\n.eqv SHIFT sll",
                ".eqv SHIFT sll\nSH<caret>IFT \$t0",
            )) {
                assertNull(info(source), source)
            }
        }
    }

    @Test
    fun `macro hints follow raw arguments and optional SPIM parentheses`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val definition = """
            .macro emit (%ignored, ${'$'}value)
              addiu ${'$'}t0, ${'$'}zero, ${'$'}value
            .end_macro
        """.trimIndent() + "\n"
        val first = "emit (<b>%ignored</b>, \$value)"
        val second = "emit (%ignored, <b>\$value</b>)"
        withContext(Dispatchers.EDT) {
            assertEquals(first, info(definition + "emit<caret> (+, 7)"))
            assertEquals(first, info(definition + "emit (<caret>+, 7)"))
            assertEquals(first, info(definition + "emit (<caret> 7"))
            assertEquals(first, info(definition + "emit ( <caret>"))
            assertEquals(second, info(definition + "emit (+, 7<caret>)"))
            assertEquals(second, info(definition + "emit ( <caret>7"))
            assertEquals(second, info(definition + "emit -7<caret>"))
            assertEquals(second, info(definition + "emit (+, <caret>bad%"))
            assertEquals("emit (%ignored, \$value)", info(definition + ".eqv CLOSE )\nemit (+, 7 C<caret>LOSE"))
            assertNull(info(definition + "emit (+, 7)<caret>"))
        }
        val entry = directory.resolve("entry.asm").also {
            it.writeText(definition + "emit (+, 7)\nemit ( 8\nemit -9\n")
        }
        val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
        assertEquals(listOf(0x24080007, 0x24080008, 0x24080009), program.machineList.map { it.binaryStatement })
    }

    @Test
    fun `macro hints follow included redefinitions aliases and edits`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            val include = fixture.addFileToProject("macros.s", ".macro emit (%stale)\n.end_macro\n")
            val source = """
                .include "macros.s"
                .macro emit (%left, %right)
                .end_macro
                .eqv CALL emit (
                entry: CALL <caret>1)
            """.trimIndent()
            fun expected(parameter: String) =
                listOf("emit (<b>$parameter</b>)", "emit (<b>%left</b>, %right)").sorted().joinToString("\n-\n")
            assertEquals(expected("%stale"), info(source))
            val editor = fixture.editor
            val file = fixture.file
            val handler = MipsParameterInfoHandler()
            val initial = MockCreateParameterInfoContext(editor, file)
            val owner = handler.findElementForParameterInfo(initial)
            val update = MockUpdateParameterInfoContext(editor, file, initial.itemsToShow)
            assertSame(owner, handler.findElementForUpdatingParameterInfo(update))
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertNull(handler.findElementForParameterInfo(MockCreateParameterInfoContext(editor, file)))
            }

            val document = FileDocumentManager.getInstance().getDocument(include.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) {
                document.setText(".macro emit (%fresh)\n.end_macro\n")
                PsiDocumentManager.getInstance(current).commitAllDocuments()
            }
            assertNull(handler.findElementForUpdatingParameterInfo(update))
            assertEquals(expected("%fresh"), fixture.parameterInfoAtCaret)
            assertNull(info("em<caret>it 1\n.macro emit (%value)\n.end_macro"))
            assertNull(info("emit <caret>1\n.macro emit (%value)\n.end_macro"))
        }
    }

    @Test
    fun `popup updates reject stale instruction forms after unsaved include edits`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            val include = fixture.addFileToProject("aliases.s", ".eqv SHIFT sll \$t0\n")
            assertEquals("sll \$t1,<b>\$t2</b>,10", info(".include \"aliases.s\"\nSHIFT <caret>\n# end"))
            val editor = fixture.editor
            val file = fixture.file
            val handler = MipsParameterInfoHandler()
            val initial = MockCreateParameterInfoContext(editor, file)
            handler.findElementForParameterInfo(initial)
            val update = MockUpdateParameterInfoContext(editor, file, initial.itemsToShow)
            val owner = file.findElementAt(file.text.indexOf("SHIFT"))!!.parent
            assertSame(owner, handler.findElementForUpdatingParameterInfo(update))
            handler.updateParameterInfo(owner, update)
            assertEquals(1, update.currentParameter)
            val operandOffset = editor.caretModel.offset
            editor.caretModel.moveToOffset(file.text.indexOf("# end") + 2)
            assertNull(handler.findElementForUpdatingParameterInfo(update))
            editor.caretModel.moveToOffset(operandOffset)

            val document = FileDocumentManager.getInstance().getDocument(include.virtualFile)!!
            fun edit(text: String) = WriteCommandAction.runWriteCommandAction(current) {
                document.setText(text)
                PsiDocumentManager.getInstance(current).commitAllDocuments()
            }
            edit(".eqv SHIFT srl \$t0\n")
            assertNull(handler.findElementForUpdatingParameterInfo(update))
            assertEquals("srl \$t1,<b>\$t2</b>,10", fixture.parameterInfoAtCaret)
            edit(".eqv SHIFT .word\n")
            assertNull(handler.findElementForUpdatingParameterInfo(update))
            handler.updateParameterInfo(owner, update)
        }
    }

    private fun info(source: String): String? {
        fixture.configureByText("parameter-info.asm", "$source\n")
        return fixture.parameterInfoAtCaret
    }
}
