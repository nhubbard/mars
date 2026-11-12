package edu.missouristate.mars.plugin.language

import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import edu.missouristate.mars.assembler.OperandFormat
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.basic.InstrMfc0
import edu.missouristate.mars.mips.instructions.basic.InstrMtc0
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.macroLineTokens

private val instructionType = MipsTypes.psi("INSTRUCTION")
private val statementType = MipsTypes.psi("STATEMENT")
internal val operationOwners = TokenSet.create(instructionType, MipsTypes.psi("OPERATION_CANDIDATE"))
internal val PsiElement.operationHead: PsiElement
    get() = if (elementType == instructionType) firstChild.firstChild else firstChild

internal fun operationOwner(position: PsiElement): PsiElement? =
    PsiTreeUtil.findFirstParent(position, true) { it.elementType in operationOwners }

/** Use the statement's head even when a malformed operand is in a recovery node. */
internal fun operationOnLine(first: PsiElement?): PsiElement? =
    PsiTreeUtil.findFirstParent(first, true) { it.elementType == statementType }
        ?.node?.findChildByType(operationOwners)?.psi

// The assembler tokenizes this operand like a GPR, although it addresses a separate bank.
internal const val COPROCESSOR0_REGISTER_INDEX = 2
internal val Instruction.hasCoprocessor0Register: Boolean get() = this is InstrMfc0 || this is InstrMtc0

private val coprocessor0Transfers by lazy {
    InstructionDefaults.get().instructionList.filter { it.hasCoprocessor0Register }.map { it.name }.toSet()
}

internal fun coprocessor0Operand(head: PsiElement): Int? {
    if (head.text.lowercase() !in coprocessor0Transfers) return null
    val tokens = macroLineTokens(head)
    if (tokens.size() <= COPROCESSOR0_REGISTER_INDEX) return null
    return head.textOffset + tokens[COPROCESSOR0_REGISTER_INDEX].startPos - 1
}

internal fun enabledInstructionFormats(name: String, allowPseudo: Boolean): List<Instruction> {
    val formats = InstructionDefaults.get().matchOperator(name)
    return if (allowPseudo) formats else formats.filterIsInstance<BasicInstruction>()
}

internal data class MipsParameterRow(val text: String, val ranges: List<TextRange>)

internal fun renderInstructionExample(instruction: Instruction, bareMachine: Boolean): MipsParameterRow {
    val example = instruction.exampleFormat
    val format = instruction.tokenList
    val text = StringBuilder(example.length)
    var previousEnd = 0
    val ranges = (1 until format.size()).map { index ->
        val token = format[index]
        val start = token.startPos - 1
        val end = token.endPos - 1
        text.append(example, previousEnd, start)
        val renderedStart = text.length
        if (bareMachine && token.type == TokenTypes.REGISTER_NAME) {
            text.append('$').append(RegisterNames.integer(token.value).number)
        } else {
            text.append(example, start, end)
        }
        previousEnd = end
        TextRange(renderedStart, text.length)
    }
    return MipsParameterRow(text.append(example, previousEnd, example.length).toString(), ranges)
}

internal class MipsInstructionOperand private constructor(
    val index: Int, val formats: List<Instruction>, private val head: PsiElement, private val beforeOperand: String,
) {
    val acceptsLabel: Boolean get() = formats.any { it.tokenList[index].type == TokenTypes.IDENTIFIER }

    fun equivalenceCompletions(file: MipsFile, prefix: String, bareMachine: Boolean): Map<String, String> {
        if (formats.isEmpty()) return emptyMap()
        return file.project.service<MarsAnalysisService>().instructionEquivalenceCompletions(file, head, beforeOperand, prefix) { tokens ->
            !tokens.isEmpty && formats.any {
                it.name.equals(tokens[0].value, ignoreCase = true) && OperandFormat.matchesOperands(tokens, it, index, bareMachine)
            }
        }
    }

    companion object {
        private val addressPunctuation = setOf(TokenTypes.LEFT_PAREN, TokenTypes.RIGHT_PAREN)

        fun at(position: PsiElement, sourceFile: MipsFile, allowPseudo: Boolean = true): MipsInstructionOperand? {
            val instruction = operationOwner(position) ?: return null
            val prefix = instruction.node.chars.subSequence(0, position.textOffset - instruction.textOffset).toString()
            // Navigation and completion use the operand slot while the instruction is being edited.
            val tokens = sourceFile.project.service<MarsAnalysisService>()
                .editorInstructionTokens(sourceFile, instruction.operationHead, prefix, allowIncompleteOperand = true) ?: return null
            val index = tokens.size()
            val formats = enabledInstructionFormats(tokens[0].value, allowPseudo).filter {
                index < it.tokenList.size() && matchesAddressShape(tokens, it.tokenList)
            }
            return MipsInstructionOperand(index, formats, instruction.operationHead, prefix)
        }

        private fun matchesAddressShape(tokens: TokenList, format: TokenList): Boolean = (1 until tokens.size()).all { index ->
            val actual = tokens[index].type
            val expected = format[index].type
            actual == expected || (actual !in addressPunctuation && expected !in addressPunctuation)
        }
    }
}
