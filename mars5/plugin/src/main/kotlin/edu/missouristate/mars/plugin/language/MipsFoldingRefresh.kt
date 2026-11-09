package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.asDisposable
import com.intellij.util.ui.update.DebouncedUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.annotations.TestOnly
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
internal class MipsFoldingRefresh(project: Project, scope: CoroutineScope) {
    // IntelliJ tracks dependencies attached to descriptors, so an empty folding result
    // needs an explicit refresh when the assembly context can introduce a fold.
    private val refresh = DebouncedUpdates.forScope<VirtualFile?>(scope, "Refresh MARS folds", 200.milliseconds)
        .withContext(Dispatchers.EDT)
        .restartTimerOnAdd(true)
        .runBatchedDistinct { changes ->
            val manager = CodeFoldingManager.getInstance(project)
            val onlyChangedFile = changes.singleOrNull()
            for (editor in FileEditorManager.getInstance(project).allEditors.filterIsInstance<TextEditor>()) {
                if (editor.file.fileType == MipsFileType && editor.file != onlyChangedFile) {
                    manager.scheduleAsyncFoldingUpdate(editor.editor)
                }
            }
        }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document)
                if (file?.fileType == MipsFileType) refresh.queue(file)
            }
        }, scope.asDisposable())
        project.messageBus.connect(scope).subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() {
                // A null source marks a context change affecting every MARS editor.
                refresh.queue(null)
            }
        })
    }

    fun assemblyEntryChanged() = refresh.queue(null)

    @TestOnly
    fun waitForRefresh() = refresh.waitForAllExecuted(5.seconds)
}
