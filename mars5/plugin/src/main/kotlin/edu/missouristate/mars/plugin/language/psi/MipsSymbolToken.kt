package edu.missouristate.mars.plugin.language.psi

import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsInstructionOperand
import edu.missouristate.mars.plugin.language.MipsTypes

internal class MipsSymbolToken(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getReference(): PsiReference? {
        val identifier = TokenTypes.isValidIdentifier(text)
        val context = PsiUtilCore.getElementType(parent)
        val parameter = elementType in parameterTypes && context in parameterContexts && Macro.tokenIsMacroParameter(text, true)
        val label = identifier && context == referenceExpression && MipsInstructionOperand.at(this)?.acceptsLabel == true
        val equivalence = identifier && elementType != mnemonic &&
            (context in equivalenceContexts || context in macroHeaderContexts)
        if (!label && !equivalence && !parameter) return null
        val data = if (equivalence) DataLabelOperand.at(this, context) else null
        return SymbolReference(this, label, equivalence, parameter, context == operationCandidate, data)
    }

    override fun getReferences(): Array<PsiReference> = listOfNotNull(reference).toTypedArray()

    private class SymbolReference(
        element: MipsSymbolToken,
        private val label: Boolean,
        private val equivalence: Boolean,
        private val parameter: Boolean,
        private val macroCall: Boolean,
        private val data: DataLabelOperand?,
    ) :
        PsiPolyVariantReferenceBase<MipsSymbolToken>(element, TextRange.from(0, element.textLength), true) {
        override fun multiResolve(incompleteCode: Boolean): Array<PsiElementResolveResult> {
            val file = element.containingFile as MipsFile
            val analysis = file.project.service<MarsAnalysisService>()
            if (equivalence) {
                val target = analysis.equivalenceTarget(file, element)
                if (target != null) return arrayOf(PsiElementResolveResult(target))
            }
            if (parameter) {
                val targets = analysis.macroParameterTargets(file, element)
                if (targets != null) return targets.map(::PsiElementResolveResult).toTypedArray()
            }
            if (macroCall) {
                val target = analysis.macroTarget(file, element)
                if (target != null) return arrayOf(PsiElementResolveResult(target))
            }
            val resolvesLabel = label || data == DataLabelOperand.EXPORT ||
                data != null && resolvesDataLabel(file, analysis)
            return if (resolvesLabel) {
                analysis.labelTargets(file, element, allowExternal = data != DataLabelOperand.EXPORT)
                    .map(::PsiElementResolveResult).toTypedArray()
            } else emptyArray()
        }

        private fun resolvesDataLabel(file: MipsFile, analysis: MarsAnalysisService): Boolean {
            if (!analysis.hasIntegerDataList(file, element)) return false
            if (data == DataLabelOperand.LIST) return true
            val head = if (macroCall) element else element.parent.parent.firstChild
            return analysis.isIntegerDataCandidate(file, head, macroCall)
        }
    }

    private companion object {
        val mnemonic = MipsTypes.psi("MNEMONIC")
        val referenceExpression = MipsTypes.psi("REFERENCE_EXPR")
        val operationCandidate = MipsTypes.psi("OPERATION_CANDIDATE")
        val equivalenceContexts = TokenSet.create(
            referenceExpression, MipsTypes.psi("OPERAND_FRAGMENT"), MipsTypes.psi("SEGMENT_ADDRESS_SYMBOL"),
            operationCandidate, MipsTypes.psi("INVOCATION_TOKEN"),
        )
        val macroHeaderContexts = TokenSet.create(MipsTypes.psi("MACRO_SIGNATURE"), MipsTypes.psi("MACRO_PARAMETER"))
        val parameterTypes = TokenSet.create(MipsTypes.psi("PARAMETER"), MipsTypes.psi("DOLLAR_WORD"))
        val parameterContexts = TokenSet.orSet(
            equivalenceContexts, TokenSet.create(MipsTypes.psi("DIRECTIVE_STATEMENT"), MipsTypes.psi("LABEL")),
        )
    }
}

private enum class DataLabelOperand {
    EXPORT, LIST, CANDIDATE;

    companion object {
        private val candidateTypes = TokenSet.create(MipsTypes.psi("OPERATION_CANDIDATE"), MipsTypes.psi("INVOCATION_TOKEN"))
        private val referenceType = MipsTypes.psi("REFERENCE_EXPR")
        private val operandType = MipsTypes.psi("OPERAND")
        private val repetitionType = MipsTypes.psi("REPETITION")
        private val continuationType = MipsTypes.psi("DATA_CONTINUATION")
        private val directiveType = MipsTypes.psi("DIRECTIVE_STATEMENT")

        fun at(element: LeafPsiElement, context: IElementType?): DataLabelOperand? {
            if (context in candidateTypes) return CANDIDATE
            if (context != referenceType) return null
            val operand = element.parent.parent
            if (PsiUtilCore.getElementType(operand) != operandType || operand.node.findChildByType(repetitionType) != null) return null
            val owner = operand.parent.parent
            return when (PsiUtilCore.getElementType(owner)) {
                continuationType -> LIST
                directiveType -> {
                    val directive = Directives.matchDirective(owner.firstChild.text)
                    when {
                        directive == Directives.GLOBL -> EXPORT
                        Directives.isIntegerDirective(directive) -> LIST
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}
