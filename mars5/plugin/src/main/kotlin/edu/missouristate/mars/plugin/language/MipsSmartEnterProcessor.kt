package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.assembler.OperandFormat
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings

internal class MipsSmartEnterProcessor : SmartEnterProcessor(), DumbAware {
    override fun process(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        commit(editor)
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val start = document.getLineStartOffset(line)
        val source = document.charsSequence
        val last = CharArrayUtil.shiftBackward(source, document.getLineEndOffset(line) - 1, " \t")
        if (last < start) return false
        val leaf = psiFile.findElementAt(last)!!
        if (leaf.isUnterminatedInclude()) {
            document.insertString(leaf.textRange.endOffset, "\"")
        } else {
            val operand = if (leaf is PsiComment) {
                val beforeComment = CharArrayUtil.shiftBackward(source, leaf.textOffset - 1, " \t")
                if (beforeComment < start) return false
                psiFile.findElementAt(beforeComment)!!
            } else leaf
            val first = CharArrayUtil.shiftForward(source, start, " \t,")
            val head = operationOnLine(psiFile.findElementAt(first))?.operationHead ?: return false

            val end = operand.textRange.endOffset
            val candidate = source.subSequence(head.textOffset, end).toString() + ")"
            val completed = project.service<MarsAnalysisService>()
                .editorInstructionTokens(psiFile as MipsFile, head, candidate) ?: return false
            val options = project.service<MarsAssemblySettings>().executionOptions
            if (enabledInstructionFormats(completed[0].value, options.allowPseudoInstructions).any { format ->
                    completed.size() == format.tokenList.size() &&
                        OperandFormat.matchesOperands(completed, format, 1, completed.size(), options.bareMachine)
                }) {
                document.insertString(end, ")")
            }
        }
        // Keep the platform's normal Start New Line behavior after a repair.
        return false
    }
}
