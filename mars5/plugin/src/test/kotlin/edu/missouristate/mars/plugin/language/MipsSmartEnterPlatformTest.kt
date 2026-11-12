package edu.missouristate.mars.plugin.language

import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

@TestApplication
class MipsSmartEnterPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `complete statement closes a physical include and starts a new line in one edit`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            val source = "prefix: .include ,, \"values # one.s  \ntail: nop\n"
            fixture.configureByText("main.asm", source.replace("values", "val<caret>ues"))
            complete()
            assertEquals(listOf("prefix: .include ,, \"values # one.s  \"", "", "tail: nop", ""), lines())
            assertEquals(1, fixture.editor.caretModel.logicalPosition.line)

            val undo = UndoManager.getInstance(current)
            undo.undo(FileEditorManager.getInstance(current).selectedEditor)
            PsiDocumentManager.getInstance(current).commitAllDocuments()
            fixture.checkResult(source)
        }
    }

    @Test
    fun `complete statement closes matching basic and extended memory operands before comments`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val cases = listOf(
                "mark: lw ${'$'}t0, -4(${'$'}sp<caret>  # keep me" to "mark: lw ${'$'}t0, -4(${'$'}sp)  # keep me",
                "    sw ${'$'}t0 (${'$'}s0<caret>" to "    sw ${'$'}t0 (${'$'}s0)",
                "    lw ${'$'}t0, item(${'$'}s0<caret>" to "    lw ${'$'}t0, item(${'$'}s0)",
            )
            for ((written, closed) in cases) {
                fixture.configureByText("main.s", "$written\ntail: nop\n")
                complete()
                assertEquals(listOf(closed, "", "tail: nop", ""), lines())
                assertEquals(1, fixture.editor.caretModel.logicalPosition.line)
            }
        }
    }

    @Test
    fun `complete statement repairs effective memory instructions from included aliases`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val current = project.get()
            val definitions = ".eqv LOAD lw\n.eqv BASE \$sp\n.eqv OFFSET 4(\n" +
                ".eqv CLOSED \$sp)\n.eqv WHOLE LOAD \$t0, OFFSET BASE\n.eqv INVALID \$f0\n"
            val include = ".include \"addresses.inc\""
            withContext(Dispatchers.EDT) { fixture.addFileToProject("addresses.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun check(written: String, closed: String) {
                    fixture.configureByText("addresses.mars", "$include\n$written\ntail: nop\n")
                    complete()
                    assertEquals(listOf(include, closed, "", "tail: nop", ""), lines(), written)
                    assertEquals(2, fixture.editor.caretModel.logicalPosition.line)
                }
                check("mark: LOAD \$t0, 4(BASE<caret>  # preserve", "mark: LOAD \$t0, 4(BASE)  # preserve")
                check("lw \$t0, OFFSET BASE<caret>", "lw \$t0, OFFSET BASE)")
                check("WHOLE<caret>", "WHOLE)")
                check("LOAD \$t0, tail(BASE<caret>", "LOAD \$t0, tail(BASE)")
                check("lw \$t0, 4(CLOSED<caret>", "lw \$t0, 4(CLOSED")
                check("LOAD \$t0, 4(INVALID<caret>", "LOAD \$t0, 4(INVALID")
                check("emit 4(BASE<caret>", "emit 4(BASE")
                val settings = current.service<MarsAssemblySettings>()
                settings.changeAssemblerOptions(false, warningsAreErrors = false)
                check("LOAD \$t0, tail(BASE<caret>", "LOAD \$t0, tail(BASE")
                settings.changeBareMachine(true)
                check("LOAD \$8, 4(BASE<caret>", "LOAD \$8, 4(BASE")
                check("LOAD \$8, 4(\$29<caret>", "LOAD \$8, 4(\$29)")
                fixture.file.text
            }
            directory.resolve("addresses.inc").writeText(definitions)
            val entry = directory.resolve("addresses.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            assertEquals(listOf(0x8fa80004.toInt(), 0), context.assemble(listOf(entry), null, false, false).machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `complete statement closes macro wrappers only when they select a visible overload`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val current = project.get()
            val definitions = ".eqv CALL emit\n.eqv WHOLE CALL(\$t0, 7\n.eqv OPEN_QUOTE \"text\n" +
                ".macro emit (%dst, %value)\naddiu %dst, \$zero, %value\n.end_macro\n" +
                ".macro empty\n.end_macro\n.macro quote (%value)\n.end_macro\n" +
                ".macro dual (%value)\n.end_macro\n.macro dual (%raw, %value)\n.end_macro\n" +
                ".macro three (%directive, %name, %value)\n.end_macro\n"
            val include = ".include \"macros.inc\""
            withContext(Dispatchers.EDT) { fixture.addFileToProject("macros.inc", definitions) }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val completed = withContext(Dispatchers.EDT) {
                fun check(written: String, expected: String, tail: String = "tail: nop\n") {
                    fixture.configureByText("macro-repair.mars", "$include\n$written\n$tail")
                    complete()
                    assertEquals(listOf(include, expected, "") + tail.lines(), lines(), written)
                    assertEquals(2, fixture.editor.caretModel.logicalPosition.line)
                }
                check("emit(\$t0, 7<caret>", "emit(\$t0, 7)")
                check("empty(<caret>", "empty()")
                check("emit(\$t0<caret>", "emit(\$t0")
                check("emit(\$t0)<caret>", "emit(\$t0)")
                check("dual(7<caret>", "dual(7")
                check("emit \$t0, 7<caret>", "emit \$t0, 7")
                check("emit(\$t0, @<caret>", "emit(\$t0, @")
                check("quote(OPEN_QUOTE \" ) # end\"<caret>", "quote(OPEN_QUOTE \" ) # end\"")
                check("three(.eqv SIDE_EFFECT 7<caret>", "three(.eqv SIDE_EFFECT 7")
                check("later(<caret>", "later(", ".macro later\n.end_macro\n")
                fixture.configureByText("macro-repair.mars", "$include\n.macro wrapper\nemit(\$t0, 7<caret>\n.end_macro\n")
                complete()
                assertEquals(listOf(include, ".macro wrapper", "emit(\$t0, 7", "", ".end_macro", ""), lines())
                fixture.configureByText("macro-repair.mars", "$include\nemit(\$t0, 7<caret>\n")
                DumbModeTestUtils.runInDumbModeSynchronously(current) { complete() }
                assertEquals(listOf(include, "emit(\$t0, 7", "", ""), lines())
                check("entry: WHOLE<caret>  # preserve", "entry: WHOLE)  # preserve")
                fixture.file.text
            }
            directory.resolve("macros.inc").writeText(definitions)
            val entry = directory.resolve("macro-repair.mars").also { it.writeText(completed) }
            val context = current.service<MarsSessionService>().context
            assertEquals(listOf(0x24080007, 0), context.assemble(listOf(entry)).machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `complete statement preserves a valid macro invocation in another entry`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val current = project.get()
            val incomplete = ".macro emit (%value)\n.end_macro\n.include \"shared.inc\"\n"
            val valid = ".macro emit (%raw, %value)\naddiu \$t0, \$zero, %value\n.end_macro\n.include \"shared.inc\"\n"
            val shared = "emit(7\n"
            val selected = withContext(Dispatchers.EDT) {
                val entry = fixture.addFileToProject("incomplete.mars", incomplete)
                fixture.addFileToProject("valid.mars", valid)
                fixture.addFileToProject("shared.inc", shared)
                entry.virtualFile.toNioPath()
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            assertEquals(false, current.service<MarsBuildService>().assemble(selected))
            val result = withContext(Dispatchers.EDT) {
                val included = fixture.findFileInTempDir("shared.inc")!!
                fixture.configureFromExistingVirtualFile(included)
                fixture.editor.caretModel.moveToOffset(shared.indexOf('7') + 1)
                complete()
                assertEquals(listOf("emit(7", "", ""), lines())
                fixture.file.text
            }
            directory.resolve("shared.inc").writeText(result)
            val entry = directory.resolve("valid.mars").also { it.writeText(valid) }
            val context = current.service<MarsSessionService>().context
            assertEquals(listOf(0x24080007), context.assemble(listOf(entry)).machineList!!.map { it.binaryStatement })
        }

    @Test
    fun `complete statement repairs a shared operand when every entry agrees`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val current = project.get()
            val stack = ".eqv FETCH lw\n.eqv BASE \$sp\n.include \"operand.inc\"\n"
            val frame = stack.replace("\$sp", "\$fp")
            val operand = "FETCH \$t0, 4(BASE"
            val shared = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("stack.mars", stack)
                fixture.addFileToProject("frame.mars", frame)
                fixture.addFileToProject("operand.inc", "$operand\n").virtualFile.also {
                    fixture.configureFromExistingVirtualFile(it)
                }
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(current)
            val repaired = withContext(Dispatchers.EDT) {
                fixture.editor.caretModel.moveToOffset(operand.length)
                complete()
                assertEquals(listOf("$operand)", "", ""), lines())
                fixture.file.text.also {
                    UndoManager.getInstance(current).undo(FileEditorManager.getInstance(current).selectedEditor)
                    PsiDocumentManager.getInstance(current).commitAllDocuments()
                }
            }
            assertEquals(false, current.service<MarsBuildService>().assemble(shared.toNioPath()))
            withContext(Dispatchers.EDT) {
                fixture.editor.caretModel.moveToOffset(operand.length)
                complete()
                assertEquals(listOf(operand, "", ""), lines())
            }
            directory.resolve("operand.inc").writeText(repaired)
            val context = current.service<MarsSessionService>().context
            for ((name, source, encoding) in listOf(
                Triple("stack.mars", stack, 0x8fa80004.toInt()), Triple("frame.mars", frame, 0x8fc80004.toInt()),
            )) {
                val entry = directory.resolve(name).also { it.writeText(source) }
                assertEquals(listOf(encoding), context.assemble(listOf(entry)).machineList!!.map { it.binaryStatement })
            }
        }

    @Test
    fun `complete statement leaves source alone when no safe delimiter is missing`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for (source in listOf(
                "<caret>",
                "# .include \"path<caret>",
                ".asciiz \"path<caret>",
                "lw ${'$'}t0, 0(${'$'}sp)<caret>",
                "lw ${'$'}t0, 0(<caret>",
                "emit<caret>",
                "emit (${'$'}sp<caret>",
                ".word ${'$'}sp<caret>",
                "add ${'$'}t0, ${'$'}t1, (${'$'}sp<caret>",
                "lw ${'$'}t0, @(${'$'}sp<caret>",
            )) {
                fixture.configureByText("main.asm", "$source\ntail: nop\n")
                complete()
                assertEquals(listOf(source.removeSuffix("<caret>"), "", "tail: nop", ""), lines(), source)
            }
        }
    }

    private fun complete() = fixture.performEditorAction(ACTION_EDITOR_COMPLETE_STATEMENT)

    private fun lines() = fixture.editor.document.text.lines().map { if (it.isBlank()) "" else it }
}
