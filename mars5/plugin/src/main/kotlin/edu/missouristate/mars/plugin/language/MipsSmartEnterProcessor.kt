package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.OperandFormat
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.instructions.InstructionDefaults

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
            if (operand.elementType != register) return false
            val first = CharArrayUtil.shiftForward(source, start, " \t,")
            val head = operationOnLine(psiFile.findElementAt(first))?.operationHead ?: return false
            if (head.elementType != mnemonic) return false

            val end = operand.textRange.endOffset
            val errors = ErrorList()
            val completed = Tokenizer().tokenizeLine(1, source.subSequence(head.textOffset, end).toString() + ")", errors, false)
            if (!errors.errorsOccurred() &&
                InstructionDefaults.get().matchOperator(head.text).any { OperandFormat.matches(completed, it) }
            ) {
                document.insertString(end, ")")
            }
        }
        // Keep the platform's normal Start New Line behavior after a repair.
        return false
    }

    private companion object {
        val register = MipsTypes.psi("REGISTER")
        val mnemonic = MipsTypes.psi("MNEMONIC")
    }
}
