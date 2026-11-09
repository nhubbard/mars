package edu.missouristate.mars.plugin.build

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import edu.missouristate.mars.plugin.language.MipsFileType

internal open class MarsAssembleAction(
    private val entry: VirtualFile? = null,
    private val mode: MarsAssemblyMode = MarsAssemblyMode.SINGLE,
) : DumbAwareAction() {
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
        project.service<MarsBuildService>().assembleInBackground(MarsSourceSet(file.toNioPath(), mode))
    }

    private fun sourceFile(e: AnActionEvent) = (entry ?: e.getData(CommonDataKeys.VIRTUAL_FILE))?.takeIf {
        it.isLocalSource() && it.fileType == MipsFileType
    }
}

internal class MarsAssembleDirectoryAction : MarsAssembleAction(mode = MarsAssemblyMode.DIRECTORY)

internal class MarsAssembleProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.service<MarsAssemblySettings>()?.projectSourceSet() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sources = project.service<MarsAssemblySettings>().projectSourceSet() ?: return
        project.service<MarsBuildService>().assembleInBackground(sources)
    }
}

internal fun VirtualFile.isLocalSource() =
    isValid && !isDirectory && fileSystem === LocalFileSystem.getInstance()
