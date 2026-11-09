package edu.missouristate.mars.plugin.language

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults

internal class MipsParameterInfoHandler : ParameterInfoHandler<PsiElement, Instruction>, DumbAware {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val instruction = findInstruction(context) ?: return null
        context.itemsToShow = InstructionDefaults.get().matchOperator(instruction.firstChild.text)
            .distinctBy { it.exampleFormat }.toTypedArray()
        return instruction
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? = findInstruction(context)

    // Unlike Java calls, a MIPS instruction can use whitespace alone to separate operands.
    override fun isWhitespaceSensitive(): Boolean = true

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val text = context.editor.document.immutableCharSequence
        val prefix = text.subSequence(parameterOwner.textOffset, context.offset).toString()
        val tokens = Tokenizer().tokenizeLine(1, prefix, ErrorList(), false)
        val count = tokens.size()
        val last = tokens[count - 1]
        val atNextToken = count == 1 || last.endPos - 1 < prefix.length || last.type in operandPrefixes
        context.setCurrentParameter(if (atNextToken) count - 1 else count - 2)
    }

    override fun updateUI(instruction: Instruction, context: ParameterInfoUIContext) {
        val index = context.currentParameterIndex + 1 // Catalog token zero is the mnemonic.
        val tokens = instruction.tokenList
        val token = if (index in 1 until tokens.size()) tokens[index] else null
        context.setupUIComponentPresentation(
            instruction.exampleFormat, token?.let { it.startPos - 1 } ?: -1, token?.let { it.endPos - 1 } ?: -1,
            false, false, false, context.defaultParameterColor,
        )
    }

    private fun findInstruction(context: ParameterInfoContext): PsiElement? {
        val document = context.editor.document
        val offset = context.offset
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val first = CharArrayUtil.shiftForward(document.immutableCharSequence, lineStart, " \t,")
        if (first >= offset || PsiUtilCore.getElementType(context.file.findElementAt(offset - 1)) == commentType) return null
        val statement = PsiTreeUtil.findFirstParent(context.file.findElementAt(first), true) {
            PsiUtilCore.getElementType(it) == statementType
        } ?: return null
        val owner = statement.node.findChildByType(instructionType)?.psi ?: return null
        return owner.takeIf { offset >= it.firstChild.textRange.endOffset }
    }

    private companion object {
        val statementType = MipsTypes.psi("STATEMENT")
        val instructionType = MipsTypes.psi("INSTRUCTION")
        val commentType = MipsTypes.psi("COMMENT")
        val operandPrefixes = setOf(TokenTypes.LEFT_PAREN, TokenTypes.PLUS, TokenTypes.MINUS)
    }
}
