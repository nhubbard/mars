package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandAction
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import edu.missouristate.mars.plugin.language.psi.mipsIncludeSites

/** The include prepass examines the physical line before resolving symbols or choosing a build entry. */
internal class MipsUnterminatedIncludeAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (!element.isUnterminatedInclude()) return

        holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.include.unterminated"))
            .range(element.textRange)
            .withFix(CloseIncludeQuote)
            .create()
    }
}

private object CloseIncludeQuote : PsiUpdateModCommandAction<PsiElement>(PsiElement::class.java), DumbAware {
    override fun getFamilyName(): String = bundle.getMessage("syntax.include.closeQuote")

    override fun isElementApplicable(element: PsiElement, context: ActionContext): Boolean =
        element.isUnterminatedInclude()

    override fun invoke(context: ActionContext, element: PsiElement, updater: ModPsiUpdater) {
        updater.document.insertString(element.textRange.endOffset, "\"")
    }
}

private val unclosedString = MipsTypes.psi("UNCLOSED_STRING")
private val bundle = DynamicBundle(MipsUnterminatedIncludeAnnotator::class.java, "messages.MarsBundle")

private fun PsiElement.isUnterminatedInclude(): Boolean =
    elementType == unclosedString && textOffset in mipsIncludeSites(containingFile).unterminated
