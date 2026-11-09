package edu.missouristate.mars.plugin.language.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.TokenSet
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsInstructionOperand
import edu.missouristate.mars.plugin.language.MipsTypes

internal class MipsOperandReferenceElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? {
        if (node.firstChildNode.elementType !in labelTokens || !TokenTypes.isValidIdentifier(text)) return null
        if (MipsInstructionOperand.at(this)?.acceptsLabel != true) return null
        return LabelReference(this)
    }

    override fun getReferences(): Array<PsiReference> = listOfNotNull(reference).toTypedArray()

    private class LabelReference(element: MipsOperandReferenceElement) :
        PsiPolyVariantReferenceBase<MipsOperandReferenceElement>(element, TextRange.from(0, element.textLength), true) {
        override fun multiResolve(incompleteCode: Boolean): Array<PsiElementResolveResult> {
            val file = element.containingFile as MipsFile
            return file.project.service<MarsAnalysisService>().labelTargets(file, element)
                .map(::PsiElementResolveResult).toTypedArray()
        }
    }

    private companion object {
        val labelTokens = TokenSet.create(MipsTypes.psi("WORD"), MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("MNEMONIC"))
    }
}
