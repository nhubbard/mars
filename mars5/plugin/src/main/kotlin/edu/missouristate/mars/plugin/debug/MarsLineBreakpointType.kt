package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.elementType
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.run.runMessages

internal class MarsLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(
    "MarsMipsLine", runMessages.getMessage("debug.breakpoint.type"),
) {
    private val instruction = MipsTypes.psi("INSTRUCTION")
    private val operationCandidate = MipsTypes.psi("OPERATION_CANDIDATE")

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun canBeHitInOtherPlaces() = true

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        if (file.fileSystem !== LocalFileSystem.getInstance() || file.fileType !== MipsFileType) return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        var candidate = false
        XDebuggerUtil.getInstance().iterateLine(project, document, line) { leaf ->
            candidate = generateSequence(leaf) { it.parent }.any {
                when (it.elementType) {
                    instruction -> true
                    operationCandidate -> it.firstChild === leaf &&
                        project.service<MarsAnalysisService>().canSetOperationBreakpoint(leaf.containingFile as MipsFile, leaf)
                    else -> false
                }
            }
            !candidate
        }
        return candidate
    }
}
