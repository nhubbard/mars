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
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsInstructionOperand
import edu.missouristate.mars.plugin.language.MipsTypes

internal class MipsSymbolToken(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getReference(): PsiReference? {
        if (!TokenTypes.isValidIdentifier(text)) return null
        val context = PsiUtilCore.getElementType(parent)
        val label = context == referenceExpression && MipsInstructionOperand.at(this)?.acceptsLabel == true
        val equivalence = elementType != mnemonic && context in equivalenceContexts
        if (!label && !equivalence) return null
        return SymbolReference(this, label, equivalence, context == operationCandidate)
    }

    override fun getReferences(): Array<PsiReference> = listOfNotNull(reference).toTypedArray()

    private class SymbolReference(
        element: MipsSymbolToken,
        private val label: Boolean,
        private val equivalence: Boolean,
        private val macroCall: Boolean,
    ) :
        PsiPolyVariantReferenceBase<MipsSymbolToken>(element, TextRange.from(0, element.textLength), true) {
        override fun multiResolve(incompleteCode: Boolean): Array<PsiElementResolveResult> {
            val file = element.containingFile as MipsFile
            val analysis = file.project.service<MarsAnalysisService>()
            if (equivalence) {
                val target = analysis.equivalenceTarget(file, element)
                if (target != null) return arrayOf(PsiElementResolveResult(target))
            }
            if (macroCall) {
                val target = analysis.macroTarget(file, element)
                if (target != null) return arrayOf(PsiElementResolveResult(target))
            }
            return if (label) analysis.labelTargets(file, element).map(::PsiElementResolveResult).toTypedArray() else emptyArray()
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
    }
}
