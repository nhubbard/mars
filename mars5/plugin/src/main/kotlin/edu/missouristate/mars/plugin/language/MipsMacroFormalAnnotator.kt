package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots

/** Reports invalid expanded formals from the same timeline used for macro scope and navigation. */
internal class MipsMacroFormalAnnotator : Annotator {
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
            if (!hasEntry) return
            val site = file.findElementAt(invalid.offset)!!
            holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.macro.formal.expanded", invalid.value))
                .range(site.textRange).create()
        }
    }

    private companion object {
        val bundle = DynamicBundle(MipsMacroFormalAnnotator::class.java, "messages.MarsBundle")
    }
}
