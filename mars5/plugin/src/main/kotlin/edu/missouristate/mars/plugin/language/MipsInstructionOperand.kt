package edu.missouristate.mars.plugin.language

import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.basic.InstrMfc0
import edu.missouristate.mars.mips.instructions.basic.InstrMtc0
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

// The assembler tokenizes this operand like a GPR, although it addresses a separate bank.
internal const val COPROCESSOR0_REGISTER_INDEX = 2
internal val Instruction.hasCoprocessor0Register: Boolean get() = this is InstrMfc0 || this is InstrMtc0

internal fun enabledInstructionFormats(name: String, allowPseudo: Boolean): List<Instruction> {
    val formats = InstructionDefaults.get().matchOperator(name)
    return if (allowPseudo) formats else formats.filterIsInstance<BasicInstruction>()
}

internal class MipsInstructionOperand(val index: Int, val formats: List<Instruction>) {
    val acceptsLabel: Boolean get() = formats.any { it.tokenList[index].type == TokenTypes.IDENTIFIER }

    companion object {
        private val addressPunctuation = setOf(TokenTypes.LEFT_PAREN, TokenTypes.RIGHT_PAREN)

        fun at(position: PsiElement, sourceFile: MipsFile, allowPseudo: Boolean = true): MipsInstructionOperand? {
            val instruction = operationOwner(position) ?: return null
            val prefix = instruction.node.chars.subSequence(0, position.textOffset - instruction.textOffset).toString()
            val tokens = sourceFile.project.service<MarsAnalysisService>()
                .editorInstructionTokens(sourceFile, instruction.operationHead, prefix) ?: return null
            val index = tokens.size()
            val formats = enabledInstructionFormats(tokens[0].value, allowPseudo).filter {
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
