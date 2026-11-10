package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiReference
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch.SearchParameters
import com.intellij.util.Processor
import edu.missouristate.mars.plugin.language.psi.MipsMacroFormal

/** An alias in the signature can supply names other than the written token's name. */
internal class MipsMacroFormalReferencesSearcher : QueryExecutorBase<PsiReference, SearchParameters>(true) {
    override fun processQuery(queryParameters: SearchParameters, consumer: Processor<in PsiReference>) {
        val formal = queryParameters.elementToSearch as? MipsMacroFormal ?: return
        for (name in formal.effectiveNames() - formal.name) {
            queryParameters.optimizer.searchWord(name, queryParameters.effectiveSearchScope, UsageSearchContext.IN_CODE, true, formal)
        }
    }
}
