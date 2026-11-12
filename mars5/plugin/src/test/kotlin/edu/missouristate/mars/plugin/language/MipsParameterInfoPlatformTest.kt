package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `parameter info shows only supported forms when pseudo instructions are disabled`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            project.get().service<MarsAssemblySettings>().changeAssemblerOptions(false, warningsAreErrors = false)
            assertEquals("div \$t1,<b>\$t2</b>", info("div \$t0, <caret>"))
            assertEquals("div \$t1,<b>\$t2</b>", info(".eqv DIVIDE div\nDIVIDE \$t0, <caret>"))
            assertNull(info("li <caret>"))
            assertEquals("emit (<b>%arg</b>)", info(".macro emit (%arg)\nnop\n.end_macro\nemit(<caret>)"))
        }
    }

    @Test
    fun `bare-machine parameter info renders numeric registers and preserves operand highlights`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val settings = project.get().service<MarsAssemblySettings>()
            settings.changeBareMachine(true)
            fun rows(source: String) = info(source).orEmpty().split("\n-\n")
            assertEquals(
                setOf("add <b>\$9</b>,\$10,\$11", "add <b>\$9</b>,\$10,-100", "add <b>\$9</b>,\$10,100000"),
                rows("add <caret>").toSet(),
            )
            assertTrue("add \$9,\$10,<b>\$11</b>" in rows("add \$8, \$0, <caret>"))
            val memory = info("lw \$8, -4(<caret>)").orEmpty().split("\n-\n")
            assertTrue("lw \$9,-100(<b>\$10</b>)" in memory)
            assertEquals("sll \$9,<b>\$10</b>,10", info(".eqv SHIFT sll \$8\nSHIFT <caret>"))
            assertEquals("add.s \$f0,<b>\$f1</b>,\$f3", info("add.s \$f0, <caret>"))
            assertEquals("mtc0 \$9,<b>\$8</b>", info("mtc0 \$8, <caret>"))
            assertEquals("emit (<b>\$arg</b>)", info(".macro emit (\$arg)\nnop\n.end_macro\nemit(<caret>)"))

            settings.changeAssemblerOptions(false, warningsAreErrors = false)
            assertEquals("div \$9,<b>\$10</b>", info("div \$8, <caret>"))
            assertNull(info("li <caret>"))
            settings.changeBareMachine(false)
            assertEquals("add \$t1,\$t2,<b>\$t3</b>", info("add \$t0, \$zero, <caret>"))
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
            val file = fixture.configureByText("uncommitted-parameter.asm", "nop\n")
            val editor = fixture.editor
            val document = editor.document
            WriteCommandAction.runWriteCommandAction(project.get()) {
                document.insertString(document.textLength, "sll ")
            }
            editor.caretModel.moveToOffset(document.textLength)
            assertFalse(PsiDocumentManager.getInstance(project.get()).isCommitted(document))
            assertNull(MipsParameterInfoHandler().findElementForParameterInfo(MockCreateParameterInfoContext(editor, file)))
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
                DEFINE REGS ${'$'}t0,${'$'}t1
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
            assertEquals(info("lw \$t0, -4(<caret>"), info("${include}entry: lw \$t0, OFFSET<caret> \$sp)"))
            assertEquals("sll \$t1,\$t2,<b>10</b>", info("${include}entry: sll REGS, <caret>4"))
            DumbModeTestUtils.runInDumbModeSynchronously(project.get()) {
                assertEquals("sll \$t1,<b>\$t2</b>,10", fixture.parameterInfoAtCaret)
            }
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
                ".eqv FIRST SECOND\n.eqv SECOND FIRST\nsll FIRST, <caret>",
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
            assertEquals(first, info(definition + "entry: emit:<caret> 7"))
            assertEquals(second, info(definition + "entry: emit: <caret>7"))
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
    fun `native parameter info distinguishes a repetition from a matching macro`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val setup = ".macro emit (%one)\n.end_macro\n.data\n.word 0\n"
            assertNull(info(setup + "emit <caret>7:2"))
            assertEquals("emit (<b>%one</b>)", info(setup + "emit <caret>7"))
            assertEquals("emit (<b>%one</b>)", info(setup + ".text\nemit <caret>7:2"))
        }
    }

    @Test
    fun `parameter info counts later operands after malformed tokens without expanding a cycle`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val definition = ".macro emit (%first, %second, %third)\nnop\n.end_macro\n"
            val second = "emit (%first, <b>%second</b>, %third)"
            val third = "emit (%first, %second, <b>%third</b>)"
            assertEquals("emit (<b>%first</b>, %second, %third)", info(definition + "emit(0x<caret>, \$t0, target)"))
            assertEquals(second, info(definition + "emit(0x, \$t<caret>0, target)"))
            assertEquals(third, info(definition + "emit(0x, \$t0, <caret>target)"))
            assertEquals(third, info(definition + "emit(0x, \$t0, <caret>"))
            assertEquals(third, info(definition + ".eqv CALL emit\nCALL(0x, \$t0, <caret>target)"))
            assertNull(info(definition + "emit(0x, \$t0, target)<caret>"))
            assertEquals("sll \$t1,\$t2,<b>10</b>",
                info(".eqv SHIFT sll \$t0\nSHIFT bad%, <caret>4"))
            val cycle = ".eqv FIRST SECOND\n.eqv SECOND FIRST\n"
            assertEquals(second, info(definition + cycle + "emit(0x, <caret>FIRST, target)"))
            assertNull(info(definition + cycle + "emit(FIRST, \$t0, <caret>target)"))
        }
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
    fun `direct hints use live signatures from plain includes even when a call is incomplete`() = timeoutRunBlocking<Unit> {
        val source = """
            .macro emit (%written, %obsolete)
            .end_macro
            .include "signature.txt"
            emit(7, -27)
            extra 4
            nop
        """.trimIndent()
        val definitions = ".macro emit (%first, %second)\n.end_macro\n.macro extra (%only)\n.end_macro\n"
        val include = withContext(Dispatchers.EDT) { fixture.addFileToProject("signature.txt", definitions) }
        val current = configureNested(source)
        val call = source.indexOf("emit(7")
        val extra = source.indexOf("extra 4")
        withContext(Dispatchers.EDT) {
            assertEquals("emit (<b>%first</b>, %second)", hintAt(call + "emit(".length))
            assertEquals("emit (%first, <b>%second</b>)", hintAt(call + "emit(7, -2".length))
            assertNull(hintAt(call + "emit(7, -27)".length))
            assertEquals("extra (<b>%only</b>)", hintAt(extra + "extra ".length))

            val document = FileDocumentManager.getInstance().getDocument(include.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) { document.setText(definitions.replace("%only", "%updated")) }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
            assertEquals("extra (<b>%updated</b>)", hintAt(extra + "extra ".length))
            assertEquals("emit (%first, <b>%second</b>)", info(".include \"signature.txt\"\nemit(7, <caret>"))
            assertEquals("emit (%first, <b>%second</b>)", info(".include \"missing.txt\"\n.include \"signature.txt\"\nemit(7, <caret>"))
        }
    }

    @Test
    fun `nested hints use the selected definitions and discard stale rows after an edit`() = timeoutRunBlocking<Unit> {
        val source = """
            .eqv CALL inner
            .eqv ARG 1
            .macro inner (%old, %ignored)
            .end_macro
            .macro dormant
                CALL (1, 2)
            .end_macro
            .macro outer (%value)
                CALL (ARG, -17)
            .end_macro
            .macro inner (%first, %second)
            .end_macro
            outer(7)
            .macro inner (%left, %right)
            .end_macro
            outer(8)
            nop
        """.trimIndent()
        val current = configureNested(source)
        val dormant = source.indexOf("CALL (")
        val call = source.lastIndexOf("CALL (")
        withContext(Dispatchers.EDT) {
            assertNull(hintAt(call + "CALL".length))
            fixture.doHighlighting()
            assertNull(hintAt(dormant + "CALL".length))
            assertEquals("inner (<b>%first</b>, %second)\n-\ninner (<b>%left</b>, %right)", hintAt(call + "CALL".length))
            assertEquals("inner (%first, <b>%second</b>)\n-\ninner (%left, <b>%right</b>)", hintAt(call + "CALL (ARG, -1".length))
            assertEquals("inner (%first, <b>%second</b>)\n-\ninner (%left, <b>%right</b>)", hintAt(call + "CALL (ARG, ".length))
            assertEquals("inner (%first, <b>%second</b>)\n-\ninner (%left, <b>%right</b>)", hintAt(call + "CALL (ARG,".length))
            assertNull(hintAt(call + "CALL (ARG, -17)".length))
            WriteCommandAction.runWriteCommandAction(current) {
                fixture.editor.document.replaceString(source.indexOf("%right"), source.indexOf("%right") + "%right".length, "%fresh")
            }
            PsiDocumentManager.getInstance(current).commitDocument(fixture.editor.document)
            assertNull(hintAt(call + "CALL (ARG, ".length))
            fixture.doHighlighting()
            assertEquals("inner (%first, <b>%second</b>)\n-\ninner (%left, <b>%fresh</b>)", hintAt(call + "CALL (ARG, ".length))
        }
    }

    @Test
    fun `nested hints use the realized operation and avoid guessing within a multi argument alias`() = timeoutRunBlocking<Unit> {
        val source = """
            .eqv ARGS 3, 4
            .macro outer (%operation)
                %operation (ARGS)
            .end_macro
            .macro emit (%first, %second)
            .end_macro
            outer(emit)
            nop
        """.trimIndent()
        configureNested(source)
        val call = source.indexOf("%operation (")
        withContext(Dispatchers.EDT) {
            fixture.doHighlighting()
            assertEquals("emit (<b>%first</b>, %second)", hintAt(call + "%operation".length))
            assertEquals("emit (%first, %second)", hintAt(call + "%operation (".length))
            assertEquals("emit (%first, %second)", hintAt(call + "%operation (AR".length))
            assertNull(hintAt(call + "%operation (ARGS)".length))
        }
    }

    @Test
    fun `a substituted label separator moves the nested hint to the actual call token`() = timeoutRunBlocking<Unit> {
        val source = """
            .macro outer (%separator)
                row %separator inner 7  # leave a caret position after the argument
            .end_macro
            .macro inner (%value)
            .end_macro
            outer(:)
            nop
        """.trimIndent()
        configureNested(source)
        val call = source.indexOf("row %separator")
        withContext(Dispatchers.EDT) {
            fixture.doHighlighting()
            assertNull(hintAt(call + "row".length))
            assertEquals("inner (<b>%value</b>)", hintAt(call + "row %separator inner".length))
            assertEquals("inner (%value)", hintAt(call + "row %separator inner 7 ".length))
        }
    }

    @Test
    fun `a nested call can realize both wrapped and raw argument lists`() = timeoutRunBlocking<Unit> {
        val source = """
            .macro outer (%operation, %first)
                %operation %first 7 )
            .end_macro
            .macro wrapped (%value)
            .end_macro
            .macro raw (%sign, %number, %tail)
            .end_macro
            outer(wrapped, ()
            outer(raw, +)
            nop
        """.trimIndent()
        configureNested(source)
        val call = source.indexOf("%operation %first 7")
        withContext(Dispatchers.EDT) {
            fixture.doHighlighting()
            assertEquals("raw (%sign, %number, %tail)\n-\nwrapped (%value)", hintAt(call + "%operation %first ".length))
            assertEquals("raw (%sign, %number, <b>%tail</b>)", hintAt(call + "%operation %first 7 )".length))
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

    private suspend fun configureNested(source: String) = project.get().also { current ->
        withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("nested-info.asm", source)
            fixture.configureFromExistingVirtualFile(file.virtualFile)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
    }

    private fun hintAt(offset: Int): String? {
        fixture.editor.caretModel.moveToOffset(offset)
        return fixture.parameterInfoAtCaret
    }
}
