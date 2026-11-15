package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.launchOnShow
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.beans.PropertyChangeListener
import java.lang.ref.WeakReference
import javax.swing.JEditorPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private val bitmapDisplayKey = Key.create<WeakReference<MemoryGridDisplay>>("MARS_BITMAP_DISPLAY")

internal val VirtualFile.isBitmapDisplayFile: Boolean get() = getUserData(bitmapDisplayKey) != null

internal fun bitmapDisplayFile(sourceName: String, display: MemoryGridDisplay): VirtualFile =
    LightVirtualFile.builder()
        .name(toolsMessages.getMessage("bitmap.tab", sourceName))
        .fileType(PlainTextFileType.INSTANCE)
        .content("")
        // Recently closed tabs can outlive the process and the plugin.
        .creationTrace("MARS Bitmap Display")
        .build().apply {
            putUserData(bitmapDisplayKey, WeakReference(display))
            isWritable = false
        }

@Suppress("UnstableApiUsage")
internal fun openBitmapDisplay(project: Project, process: MarsProcessHandler, focus: Boolean) {
    FileEditorManager.getInstance(project).requestOpenFile(
        process.bitmapFile, FileEditorOpenRequest.withFocus(focus).withReuseOpen(true),
    )
}

internal class BitmapDisplayEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file.getUserData(bitmapDisplayKey)?.get() != null
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        BitmapDisplayEditor(file, file.getUserData(bitmapDisplayKey)!!.get()!!)
    override fun getEditorTypeId() = "mars.bitmap.display"
    override fun getPolicy() = FileEditorPolicy.HIDE_OTHER_EDITORS
}

internal class BitmapDisplayEditor(
    private val displayFile: VirtualFile, private val display: MemoryGridDisplay,
) : UserDataHolderBase(), FileEditor {
    private val inspectionHint = toolsMessages.getMessage("bitmap.inspect.hint")
    private lateinit var inspection: JEditorPane
    private val canvas = MemoryGridCanvas(display.snapshot().frame, "bitmap.name", inspectionHint, { index ->
        display.bitmapCell(index)?.let {
            toolsMessages.getMessage(
                "bitmap.cell", it.column.toString(), it.row.toString(),
                formatToolHex(it.address), it.rgb.toString(16).padStart(6, '0'),
            )
        }
    }) { inspection.text = it }

    private val view = panel {
        row { cell(canvas).align(Align.FILL).resizableColumn() }.resizableRow()
        row { inspection = text(inspectionHint).resizableColumn().component }
    }

    @Suppress("UnstableApiUsage")
    private val refreshJob = view.launchOnShow("Refresh MARS bitmap editor") {
        while (true) {
            withContext(Dispatchers.EDT) { refresh() }
            delay(200.milliseconds)
        }
    }

    internal fun refresh() = canvas.showFrame(display.snapshot().frame)

    override fun getFile() = displayFile
    override fun getComponent() = view
    override fun getPreferredFocusedComponent() = canvas
    override fun getName() = toolsMessages.getMessage("bitmap.name")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = displayFile.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = refreshJob.cancel()
}
