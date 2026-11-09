package edu.missouristate.mars.plugin.language

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration

internal class MipsGoToSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        FileBasedIndex.getInstance().processAllKeys(MipsDeclarationIndex.NAME, processor, scope, filter)
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val psiManager = PsiManager.getInstance(parameters.project)
        FileBasedIndex.getInstance().processValues(MipsDeclarationIndex.NAME, name, null, { file, sites ->
            val psi = psiManager.findFile(file)
            sites.asSequence()
                .mapNotNull {
                    ProgressManager.checkCanceled()
                    MipsDeclaration.findAt(psi, it.offset, name)
                }
                .all(processor::process)
        }, parameters.searchScope)
    }
}
