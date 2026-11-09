package edu.missouristate.mars.plugin.language

import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

internal class MipsInstructionOperand(val index: Int, val formats: List<Instruction>) {
    val acceptsLabel: Boolean get() = formats.any { it.tokenList[index].type == TokenTypes.IDENTIFIER }

    companion object {
        private val instructionType = MipsTypes.psi("INSTRUCTION")
        private val operationTypes = TokenSet.create(instructionType, MipsTypes.psi("OPERATION_CANDIDATE"))
        private val addressPunctuation = setOf(TokenTypes.LEFT_PAREN, TokenTypes.RIGHT_PAREN)

        fun at(position: PsiElement, sourceFile: MipsFile): MipsInstructionOperand? {
            val instruction = PsiTreeUtil.findFirstParent(position, true) {
                PsiUtilCore.getElementType(it) in operationTypes
            } ?: return null
            val prefix = instruction.node.chars.subSequence(0, position.textOffset - instruction.textOffset).toString()
            val head = instruction.firstChild
            val (mnemonic, tokens) = if (instruction.node.elementType == instructionType) {
                head.text to Tokenizer().tokenizeLine(1, prefix, ErrorList(), false)
            } else {
                val expanded = sourceFile.project.service<MarsAnalysisService>()
                    .expandedInstructionAlias(sourceFile, head, prefix) ?: return null
                expanded[0].value to expanded
            }
            val index = tokens.size()
            val formats = InstructionDefaults.get().matchOperator(mnemonic).filter {
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
