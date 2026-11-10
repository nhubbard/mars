package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import edu.missouristate.mars.plugin.language.psi.mipsIncludeSites

/** The include prepass examines the physical line before resolving symbols or choosing a build entry. */
internal class MipsUnterminatedIncludeAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.elementType != unclosedString) return
        if (element.textOffset !in mipsIncludeSites(element.containingFile).unterminated) return

        holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.include.unterminated"))
            .range(element.textRange).create()
    }

    private companion object {
        val unclosedString = MipsTypes.psi("UNCLOSED_STRING")
        val bundle = DynamicBundle(MipsUnterminatedIncludeAnnotator::class.java, "messages.MarsBundle")
    }
}
