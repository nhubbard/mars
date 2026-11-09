package edu.missouristate.mars.plugin.build

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import edu.missouristate.mars.plugin.language.MipsFileType

internal class MarsAssembleAction(private val entry: VirtualFile? = null) : DumbAwareAction() {
    init {
        if (entry != null) templatePresentation.setText(entry.presentableUrl, false)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && sourceFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = sourceFile(e) ?: return
        project.getService(MarsBuildService::class.java).assembleCurrentFile(file.toNioPath())
    }

    private fun sourceFile(e: AnActionEvent) = (entry ?: e.getData(CommonDataKeys.VIRTUAL_FILE))?.takeIf {
        it.isLocalSource() && it.fileType == MipsFileType
    }
}

internal fun VirtualFile.isLocalSource() =
    isValid && !isDirectory && fileSystem === LocalFileSystem.getInstance()
