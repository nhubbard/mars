package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.openapi.components.service
import com.intellij.psi.PsiErrorElement
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

/** Some lexically valid lines are interpreted only in their effective assembly context. */
internal class MipsAssemblySyntaxErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile as? MipsFile ?: return true
        val analysis = file.project.service<MarsAnalysisService>()
        return !supersededMacroFormalError(file, element, analysis) && !analysis.defersContextDependentError(file, element)
    }
}
