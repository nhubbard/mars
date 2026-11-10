package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@TestApplication
class MipsIncludeQuickFixPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `quick fix previews repairs and undoes a physical include shared by two entries`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val path = "values # one.s"
        val unfinished = "prefix: .InClUdE ,, \"$path\ntail: nop\n"
        val repaired = unfinished.replace("$path\n", "$path\"\n")
        val target = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("first.s", ".include \"shared.s\"\n")
            fixture.addFileToProject("second.s", ".include \"shared.s\"\n")
            val target = fixture.addFileToProject(path, ".eqv VALUE 42\n")
            val shared = fixture.addFileToProject("shared.s", unfinished)
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            fixture.editor.caretModel.moveToOffset(unfinished.indexOf(path) + 2)
            target.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val fix = requireNotNull(fixture.getAvailableIntention(closeQuote))
            fixture.checkPreviewAndLaunchAction(fix)
            fixture.checkResult(repaired)
            assertEquals(target, fixture.file.findReferenceAt(repaired.indexOf(path) + 2)?.resolve()?.containingFile?.virtualFile)
            assertNull(fixture.getAvailableIntention(closeQuote))

            val undo = UndoManager.getInstance(current)
            val editor = FileEditorManager.getInstance(current).selectedEditor
            undo.undo(editor)
            PsiDocumentManager.getInstance(current).commitAllDocuments()
            fixture.checkResult(unfinished)
            undo.redo(editor)
            PsiDocumentManager.getInstance(current).commitAllDocuments()
            fixture.checkResult(repaired)
        }
    }

    @Test
    fun `quick fix inserts only one quote at the end of the literal`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            for ((written, closed) in listOf(
                ".include \"<caret>" to ".include \"\"",
                ".include \"directory\\<caret>\nnop\n" to ".include \"directory\\\"\nnop\n",
            )) {
                fixture.configureByText("entry.asm", written)
                fixture.launchAction(closeQuote)
                fixture.checkResult(closed)
                assertNull(fixture.getAvailableIntention(closeQuote))
            }
        }
    }

    @Test
    fun `ordinary strings unused operands and comments do not get the include fix`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("part.s", "nop\n")
            for (line in listOf(
                ".asciiz \"un<caret>finished",
                ".include \"part.s\" \"un<caret>used",
                "# .include \"com<caret>ment",
                ".include \"pa<caret>rt.s\"",
            )) {
                fixture.configureByText("entry.asm", "$line\ntail: nop\n")
                assertNull(fixture.getAvailableIntention(closeQuote))
            }
        }
    }

    private val closeQuote = "Close .include path with a double quote"
}
