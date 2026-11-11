package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import edu.missouristate.mars.api.InstructionSourceMap.Location

/** Registers a native line breakpoint on the physical source of a read-only debugger snapshot. */
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
        val process = XDebuggerManager.getInstance(project).currentSession?.debugProcess as? MarsDebugProcess ?: return null
        return process.originalBreakpointLine(file, editor.caretModel.logicalPosition.line)
    }
}
