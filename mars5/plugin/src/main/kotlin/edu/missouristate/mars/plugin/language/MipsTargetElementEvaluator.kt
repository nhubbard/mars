package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration

internal class MipsTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun getNamedElement(element: PsiElement): PsiElement? =
        MipsDeclaration.enclosingSignedName(element)?.parent
}
