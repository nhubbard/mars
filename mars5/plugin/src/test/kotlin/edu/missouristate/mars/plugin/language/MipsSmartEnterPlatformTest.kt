package edu.missouristate.mars.plugin.language

import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
    fun `complete statement leaves source alone when no safe delimiter is missing`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for (source in listOf(
                "<caret>",
                "# .include \"path<caret>",
                ".asciiz \"path<caret>",
                "lw ${'$'}t0, 0(${'$'}sp)<caret>",
                "lw ${'$'}t0, 0(<caret>",
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
