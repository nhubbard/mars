package edu.missouristate.mars.plugin.build

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.impl.include.FileIncludeManager
import edu.missouristate.mars.plugin.language.MipsFileType

internal class MarsContainingEntriesGroup : ActionGroup() {
    init {
        templatePresentation.isHideGroupIfEmpty = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return EMPTY_ARRAY
        if (!selected.isLocalSource() || DumbService.isDumb(project)) return EMPTY_ARRAY
        val manager = FileIncludeManager.getManager(project)
        val visited = hashSetOf(selected)
        val pending = ArrayDeque(listOf(selected))
        val roots = ArrayList<VirtualFile>()
        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val file = pending.removeFirst()
            val includers = manager.getIncludingFiles(file, true).filter { it.fileType == MipsFileType }
            if (includers.isEmpty() && file != selected) roots.add(file)
            for (includer in includers) if (visited.add(includer)) pending.addLast(includer)
        }
        return roots.sortedBy { it.path }.map { MarsAssembleAction(it) }.toTypedArray()
    }
}
