package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.analysis.assemblySourceSyntax
import edu.missouristate.mars.plugin.analysis.unresolvedAssemblySourceSyntax
import edu.missouristate.mars.plugin.language.mipsAssemblySourceOrigin

/** Registers a native line breakpoint on the physical source of a read-only MARS editor. */
internal class MarsToggleOriginalBreakpointAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (file, location) = target(e) ?: return
        val manager = XDebuggerManager.getInstance(e.project!!).breakpointManager
        val type = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)
        val line = location.line() - 1
        WriteAction.run<RuntimeException> {
            val existing = manager.findBreakpointsAtLine(type, file, line).firstOrNull()
            if (existing == null) manager.addLineBreakpoint(type, file.url, line, null)
            else manager.removeBreakpoint(existing)
        }
    }

    private fun target(e: AnActionEvent): Pair<VirtualFile, Location>? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val line = editor.caretModel.logicalPosition.line
        file.mipsAssemblySourceOrigin?.let { original ->
            if (!original.isValid) return null
            return includeTarget(project, original, file, line, editor.document.immutableCharSequence)
        }
        val process = XDebuggerManager.getInstance(project).currentSession?.debugProcess as? MarsDebugProcess ?: return null
        return process.originalBreakpointLine(file, line)
    }

    private fun includeTarget(
        project: Project, original: VirtualFile, view: VirtualFile, line: Int, displayed: CharSequence,
    ): Pair<VirtualFile, Location>? {
        val physical = PsiManager.getInstance(project).findFile(original)
        val current = (if (physical == null) unresolvedAssemblySourceSyntax(project, original)
            else assemblySourceSyntax(physical)) ?: return null
        if (!StringUtil.equals(current.viewProvider.contents, displayed)) return null
        val type = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)
        if (!type.canPutAtOperation(view, line, project)) return null
        // Store the physical URL so the breakpoint survives a closed tab or IDE restart.
        return original to Location(original.toNioPath(), line + 1)
    }
}
