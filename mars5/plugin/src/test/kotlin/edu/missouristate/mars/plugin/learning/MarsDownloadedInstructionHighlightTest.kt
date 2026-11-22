package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@TestApplication
class MarsDownloadedInstructionHighlightTest {
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)

    @Test
    fun `missing text selection is harmless and returning to the lesson restores its current pointer`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val project = projects.get()
            val manager = FileEditorManager.getInstance(project)
            val file = fixture.addFileToProject("memory.asm", ".text\nstore_color:\n    sw \$t1, 0(\$t0)\n").virtualFile
            val other = fixture.addFileToProject("other.asm", ".text\nstore_color:\n    sw \$s1, 0(\$s0)\n").virtualFile
            manager.openFiles.forEach(manager::closeFile)
            assertNull(manager.selectedTextEditor)
            val lifetime = Disposer.newDisposable()
            try {
                highlightDownloadedInstruction(project, file.toNioPath(), null, lifetime)
                highlightDownloadedInstruction(project, file.toNioPath().resolveSibling("missing.asm"), MarsLessonAnchor("store_color", "sw", 0), lifetime)
                highlightDownloadedInstruction(project, file.toNioPath(), MarsLessonAnchor("store_color", "sw", 0), lifetime)
                manager.openFile(file, FileEditorOpenRequest.withFocus(false))
                val editor = checkNotNull(manager.selectedTextEditor)
                editor.caretModel.moveToOffset(0)
                project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
                    .selectionChanged(FileEditorManagerEvent(manager, null, null))
                assertEquals(editor.document.text.indexOf("sw "), editor.caretModel.offset)
                manager.openFile(other, FileEditorOpenRequest.withFocus(false))
                val unrelated = checkNotNull(manager.selectedTextEditor)
                unrelated.caretModel.moveToOffset(0)
                project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
                    .selectionChanged(FileEditorManagerEvent(manager, null, null))
                assertEquals(0, unrelated.caretModel.offset)
                manager.openFiles.forEach(manager::closeFile)
                assertNull(manager.selectedTextEditor)
                manager.openFile(file, FileEditorOpenRequest.withFocus(false))
                val returned = checkNotNull(manager.selectedTextEditor)
                returned.caretModel.moveToOffset(0)
                project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
                    .selectionChanged(FileEditorManagerEvent(manager, null, null))
                assertEquals(returned.document.text.indexOf("sw "), returned.caretModel.offset)
                Disposer.dispose(lifetime)
                returned.caretModel.moveToOffset(0)
                project.messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
                    .selectionChanged(FileEditorManagerEvent(manager, null, null))
                assertEquals(0, returned.caretModel.offset)
            } finally { Disposer.dispose(lifetime) }
        }
    }
}
