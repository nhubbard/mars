package edu.missouristate.mars.plugin.language

import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

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

internal class MipsInstructionOperand(val index: Int, val formats: List<Instruction>) {
    val acceptsLabel: Boolean get() = formats.any { it.tokenList[index].type == TokenTypes.IDENTIFIER }

    companion object {
        private val addressPunctuation = setOf(TokenTypes.LEFT_PAREN, TokenTypes.RIGHT_PAREN)

        fun at(position: PsiElement, sourceFile: MipsFile): MipsInstructionOperand? {
            val instruction = operationOwner(position) ?: return null
            val prefix = instruction.node.chars.subSequence(0, position.textOffset - instruction.textOffset).toString()
            val tokens = sourceFile.project.service<MarsAnalysisService>()
                .editorInstructionTokens(sourceFile, instruction.operationHead, prefix) ?: return null
            val index = tokens.size()
            val formats = InstructionDefaults.get().matchOperator(tokens[0].value).filter {
                index < it.tokenList.size() && matchesAddressShape(tokens, it.tokenList)
            }
            return MipsInstructionOperand(index, formats)
        }

        private fun matchesAddressShape(tokens: TokenList, format: TokenList): Boolean = (1 until tokens.size()).all { index ->
            val actual = tokens[index].type
            val expected = format[index].type
            actual == expected || (actual !in addressPunctuation && expected !in addressPunctuation)
        }
    }
}
