package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.openapi.components.service
import com.intellij.psi.PsiErrorElement
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService

/** The assembler skips some lexically valid statement heads in a data segment. */
internal class MipsDataStatementErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile as? MipsFile ?: return true
        return !file.project.service<MarsAnalysisService>().ignoresDataStatement(file, element)
    }
}
