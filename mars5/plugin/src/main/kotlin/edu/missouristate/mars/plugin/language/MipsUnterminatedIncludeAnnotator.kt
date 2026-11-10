package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.plugin.language.syntax.inspectMipsInclude

/** The include prepass examines the physical line before resolving symbols or choosing a build entry. */
internal class MipsUnterminatedIncludeAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.elementType != unclosedString) return
        val range = element.textRange
        val source = element.containingFile.viewProvider.contents
        val start = CharArrayUtil.shiftBackwardUntil(source, range.startOffset - 1, "\r\n") + 1
        val include = inspectMipsInclude(source, start, range.endOffset) ?: return
        if (start + include.offset() != range.startOffset) return

        holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.include.unterminated")).range(range).create()
    }

    private companion object {
        val unclosedString = MipsTypes.psi("UNCLOSED_STRING")
        val bundle = DynamicBundle(MipsUnterminatedIncludeAnnotator::class.java, "messages.MarsBundle")
    }
}
