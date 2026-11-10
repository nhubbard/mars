package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem.Kind
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots

/** Reports macro expansion errors from the same timeline used for scope and navigation. */
internal class MipsMacroDiagnosticAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? MipsFile ?: return
        val analysis = file.project.service<MarsAnalysisService>()
        val timeline = analysis.editorTimeline(file)
        val hasEntry by lazy(LazyThreadSafetyMode.NONE) {
            analysis.selectedAssembly(file) != null || mipsAssemblyRoots(file).size == 1
        }
        for (macro in timeline.localMacros(file).values) {
            ProgressManager.checkCanceled()
            val invalid = macro.invalidFormal ?: continue
            // A physical syntax error on this header already explains the rejected parameter list.
            if (PsiTreeUtil.hasErrorElements(macro.header)) continue
            val site = file.findElementAt(invalid.offset)!!
            if (PsiTreeUtil.getParentOfType(site, PsiErrorElement::class.java) != null) continue
            if (!hasEntry) return
            holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.macro.formal.expanded", invalid.value))
                .range(site.textRange).create()
        }
        for ((offset, kind) in timeline.macroBoundaryProblems(file)) {
            ProgressManager.checkCanceled()
            if (!hasEntry) return
            val site = file.findElementAt(offset)!!
            val message = when (kind) {
                Kind.TRAILING_TEXT -> "syntax.macro.end.trailing"
                Kind.UNMATCHED_END -> "syntax.macro.end.unmatched"
                Kind.MISSING_END -> "syntax.macro.end.missing"
            }
            holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage(message))
                .range(site.textRange).create()
        }
    }

    private companion object {
        val bundle = DynamicBundle(MipsMacroDiagnosticAnnotator::class.java, "messages.MarsBundle")
    }
}
