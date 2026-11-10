package edu.missouristate.mars.plugin.language

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MacroInvocation
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

internal data class MipsParameterRow(val text: String, val ranges: List<TextRange>)

internal class MipsParameterInfoHandler : ParameterInfoHandler<PsiElement, MipsParameterRow>, DumbAware {
    private data class Hint(val rows: List<MipsParameterRow>, val index: Int)

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val owner = findOperation(context) ?: return null
        val hint = hint(owner, context) ?: return null
        context.itemsToShow = hint.rows.toTypedArray()
        return owner
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        val owner = findOperation(context) ?: return null
        val hint = hint(owner, context) ?: return null
        // The IDE keeps the original rows while updating a popup. An edit to a definition can change them.
        return owner.takeIf { context.objectsToView.asList() == hint.rows }
    }

    // Instructions and macro calls can use whitespace alone to separate arguments.
    override fun isWhitespaceSensitive(): Boolean = true

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val hint = hint(parameterOwner, context) ?: return
        context.setCurrentParameter(hint.index)
    }

    private fun hint(owner: PsiElement, context: ParameterInfoContext): Hint? {
        val text = context.editor.document.immutableCharSequence
        val prefix = text.subSequence(owner.textOffset, context.offset).toString()
        val file = context.file as MipsFile
        val analysis = file.project.service<MarsAnalysisService>()
        val head = owner.operationHead
        analysis.assembledMacroInvocation(file, head, context.offset)?.let { invocation ->
            val rows = invocation.calls.map { macroRow(it.name(), it.parameters()) }.distinct().sortedBy(MipsParameterRow::text)
            return Hint(rows, invocation.argument).takeIf { rows.isNotEmpty() }
        }
        val instruction = analysis.editorInstructionTokens(file, head, prefix, allowIncompleteOperand = true)
        if (instruction != null) return instructionHint(instruction, context.offset == head.textRange.endOffset)
        return analysis.macroInvocation(file, head, prefix)?.let(::macroHint)
    }

    private fun instructionHint(tokens: TokenList, atHeadEnd: Boolean): Hint {
        val count = tokens.size()
        val last = tokens[count - 1]
        val next = count == 1 || last.endPos - 1 < tokens.processedLine.length ||
            last.type in operandPrefixes || atHeadEnd
        val rows = InstructionDefaults.get().matchOperator(tokens[0].value).distinctBy { it.exampleFormat }.map { instruction ->
            val format = instruction.tokenList
            val ranges = (1 until format.size()).map { TextRange(format[it].startPos - 1, format[it].endPos - 1) }
            MipsParameterRow(instruction.exampleFormat, ranges)
        }
        return Hint(rows, if (next) count - 1 else count - 2)
    }

    private fun macroHint(invocation: MacroInvocation): Hint? {
        val (tokens, fullTokens, parameters) = invocation
        val last = tokens[tokens.size() - 1]
        val wrapped = fullTokens?.let(Macro::hasCallParentheses) == true
        val fullCount = fullTokens?.size() ?: 0
        if (wrapped && tokens.size() == fullCount && last.type == TokenTypes.RIGHT_PAREN) return null
        // An unmatched opening parenthesis can itself be an argument if the raw arity matches.
        val openingWrapper = tokens.size() > 1 && tokens[1].type == TokenTypes.LEFT_PAREN &&
            (wrapped || parameters.none { it.size + 1 == fullCount })
        val count = tokens.size() - if (openingWrapper) 2 else 1
        val index = if (count == 0 || last.endPos - 1 < tokens.processedLine.length) count else count - 1
        val rows = parameters.map { macroRow(tokens[0].value, it) }
        return Hint(rows, index)
    }

    private fun macroRow(name: String, arguments: List<String>): MipsParameterRow {
        val text = StringBuilder(name).append(" (")
        val ranges = arguments.mapIndexed { index, argument ->
            if (index > 0) text.append(", ")
            val start = text.length
            text.append(argument)
            TextRange(start, text.length)
        }
        return MipsParameterRow(text.append(')').toString(), ranges)
    }

    override fun updateUI(row: MipsParameterRow, context: ParameterInfoUIContext) {
        val range = row.ranges.getOrNull(context.currentParameterIndex)
        context.setupUIComponentPresentation(
            row.text, range?.startOffset ?: -1, range?.endOffset ?: -1,
            false, false, false, context.defaultParameterColor,
        )
    }

    private fun findOperation(context: ParameterInfoContext): PsiElement? {
        val document = context.editor.document
        val offset = context.offset
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val first = CharArrayUtil.shiftForward(document.immutableCharSequence, lineStart, " \t,")
        if (first >= offset || PsiUtilCore.getElementType(context.file.findElementAt(offset - 1)) == commentType) return null
        val statement = PsiTreeUtil.findFirstParent(context.file.findElementAt(first), true) {
            PsiUtilCore.getElementType(it) == statementType
        } ?: return null
        val owner = statement.node.findChildByType(operationOwners)?.psi ?: return null
        return owner.takeIf { offset >= it.firstChild.textRange.endOffset }
    }

    private companion object {
        val statementType = MipsTypes.psi("STATEMENT")
        val commentType = MipsTypes.psi("COMMENT")
        val operandPrefixes = setOf(TokenTypes.LEFT_PAREN, TokenTypes.PLUS, TokenTypes.MINUS)
    }
}
