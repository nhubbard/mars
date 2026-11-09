package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.closesRootlessMipsIncludeCycle
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.psi.mipsIncludePaths

/** Reports closed include cycles where the background assembler cannot infer an entry. */
internal class MipsIncludeCycleAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val literal = element as? MipsStringLiteral ?: return
        val file = literal.containingFile as MipsFile
        val path = mipsIncludePaths(file)[literal.textOffset] ?: return
        if (file.project.service<MarsAnalysisService>().selectedAssembly(file) != null) return
        if (!closesRootlessMipsIncludeCycle(file, literal, path)) return

        holder.newAnnotation(HighlightSeverity.ERROR, bundle.getMessage("syntax.include.cycle", path))
            .range(literal.textRange).create()
    }

    private companion object {
        val bundle = DynamicBundle(MipsIncludeCycleAnnotator::class.java, "messages.MarsBundle")
    }
}
