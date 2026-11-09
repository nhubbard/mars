package edu.missouristate.mars.plugin.build

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbService
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots

internal class MarsContainingEntriesGroup : ActionGroup() {
    init {
        templatePresentation.isHideGroupIfEmpty = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return EMPTY_ARRAY
        if (!selected.isLocalSource() || DumbService.isDumb(project)) return EMPTY_ARRAY
        return mipsAssemblyRoots(project, selected).filter { it != selected }
            .sortedBy { it.path }.map { MarsAssembleAction(it) }.toTypedArray()
    }
}
