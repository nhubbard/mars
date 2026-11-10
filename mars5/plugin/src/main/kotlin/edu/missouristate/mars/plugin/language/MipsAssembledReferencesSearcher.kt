package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.ReferencesSearch.SearchParameters
import com.intellij.util.Processor
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.syntax.MipsScanner

/** Supplements IntelliJ's word search with bindings established during assembly. */
internal class MipsAssembledReferencesSearcher : QueryExecutorBase<PsiReference, SearchParameters>(true) {
    override fun processQuery(queryParameters: SearchParameters, consumer: Processor<in PsiReference>) {
        val target = queryParameters.elementToSearch as? MipsDeclaration ?: return
        if (target.kind == SymbolKind.CONSTANT) return
        val analysis = target.project.service<MarsAnalysisService>()
        val scope = queryParameters.effectiveSearchScope
        for (site in analysis.assembledUsages(target)) {
            ProgressManager.checkCanceled()
            val (file, offset) = analysis.sourceTokenPosition(site) ?: continue
            val leaf = file.findElementAt(offset)!!
            if (!PsiSearchScopeUtil.isInScope(scope, leaf)) continue
            if (target.kind != SymbolKind.MACRO && leaf.references.any { it.isReferenceTo(target) }) continue
            val end = if (file is MipsFile) leaf.textRange.endOffset
            else MipsScanner.next(file.viewProvider.contents, offset)!!.end
            val range = TextRange(offset - leaf.textOffset, end - leaf.textOffset)
            if (!consumer.process(PsiReferenceBase.Immediate(leaf, range, target))) return
        }
    }
}
