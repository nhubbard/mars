package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsIncludeQuickFixPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `quick fix previews repairs and undoes a physical include shared by two entries`() = timeoutRunBlocking {
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
            fixture.awaitMipsRefresh()
            val fix = requireNotNull(fixture.getAvailableIntention(closeQuote))
            fixture.checkPreviewAndLaunchAction(fix)
            fixture.checkResult(repaired)
            assertEquals(target, fixture.file.findReferenceAt(repaired.indexOf(path) + 2)?.resolve()?.containingFile?.virtualFile)
            fixture.awaitMipsRefresh()
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
    fun `quick fix inserts only one quote at the end of the literal`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            for ((written, closed) in listOf(
                ".include \"<caret>" to ".include \"\"",
                ".include \"directory\\<caret>\nnop\n" to ".include \"directory\\\"\nnop\n",
            )) {
                fixture.configureByText("entry.asm", written)
                fixture.awaitMipsRefresh()
                fixture.launchAction(closeQuote)
                fixture.checkResult(closed)
                fixture.awaitMipsRefresh()
                assertNull(fixture.getAvailableIntention(closeQuote))
            }
        }
    }

    @Test
    fun `create include quick fix uses the directory the assembler resolves`() = timeoutRunBlocking {
        val name = "values # one.s"
        val text = ".include \"shortcut/../$name\"\nnop\n"
        val entry = withContext(Dispatchers.EDT) {
            val source = fixture.addFileToProject("local/entry.asm", text).virtualFile
            val child = fixture.tempDirFixture.findOrCreateDir("actual/nested")
            val shortcut = Files.createSymbolicLink(source.toNioPath().resolveSibling("shortcut"), child.toNioPath())
            checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(shortcut))
            fixture.configureFromExistingVirtualFile(source)
            fixture.editor.caretModel.moveToOffset(text.indexOf(name))
            source
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.awaitMipsRefresh()
            val fix = fixture.availableIntentions.single { it.text.contains(name) && it.text.startsWith("Create file") }
            fixture.launchAction(fix)
            val created = fixture.tempDirFixture.getFile("actual/$name")
            assertEquals("", created?.contentsToByteArray()?.decodeToString())
            assertNull(fixture.tempDirFixture.getFile("local/$name"))
            fixture.configureFromExistingVirtualFile(entry)
            fixture.checkResult(text)
            assertTrue(fixture.highlightAfterMipsRefresh().none { it.description?.startsWith("Error reading include file") == true })
        }
    }

    @Test
    fun `create include quick fix creates missing folders below the resolved ancestor`() = timeoutRunBlocking {
        val path = "missing/nested/new.s"
        val source = ".include \"$path\"\nnop\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("new.s", "# existing sibling\n")
            val entry = fixture.addFileToProject("entry.asm", source).virtualFile
            fixture.configureFromExistingVirtualFile(entry)
            fixture.editor.caretModel.moveToOffset(source.indexOf("new.s"))
            fixture.awaitMipsRefresh()
            val fix = fixture.availableIntentions.single { it.text == "Create file $path" }
            fixture.launchAction(fix)
            assertEquals("", fixture.tempDirFixture.getFile(path)?.contentsToByteArray()?.decodeToString())
            assertEquals("# existing sibling\n", fixture.tempDirFixture.getFile("new.s")?.contentsToByteArray()?.decodeToString())
            fixture.configureFromExistingVirtualFile(entry)
            fixture.checkResult(source)
            assertTrue(fixture.highlightAfterMipsRefresh().none { it.description?.startsWith("Error reading include file") == true })
        }
    }

    @Test
    fun `create include quick fix rejects paths without a safe directory`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            fixture.tempDirFixture.createFile("existing.s", "nop\n")
            fixture.tempDirFixture.findOrCreateDir("directory")
            for (path in listOf("missing/../new.s", "existing.s/nested/new.s", "directory", "bad\u0000name.s")) {
                fixture.configureByText("entry.asm", ".include \"<caret>$path\"\n")
                fixture.awaitMipsRefresh()
                assertTrue(fixture.availableIntentions.none { it.text.startsWith("Create file") })
            }
            val readOnly = fixture.tempDirFixture.findOrCreateDir("read-only")
            WriteAction.run<IOException> { readOnly.isWritable = false }
            try {
                fixture.configureByText("entry.asm", ".include \"read-only/<caret>new.s\"\n")
                fixture.awaitMipsRefresh()
                assertTrue(fixture.availableIntentions.none { it.text.startsWith("Create file") })
            } finally {
                WriteAction.run<IOException> { readOnly.isWritable = true }
            }
        }
    }

    @Test
    fun `ordinary strings unused operands and comments do not get the include fix`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("part.s", "nop\n")
            for (line in listOf(
                ".asciiz \"un<caret>finished",
                ".include \"part.s\" \"un<caret>used",
                "# .include \"com<caret>ment",
                ".include \"pa<caret>rt.s\"",
            )) {
                fixture.configureByText("entry.asm", "$line\ntail: nop\n")
                fixture.awaitMipsRefresh()
                assertNull(fixture.getAvailableIntention(closeQuote))
            }
        }
    }

    private val closeQuote = "Close .include path with a double quote"
}
