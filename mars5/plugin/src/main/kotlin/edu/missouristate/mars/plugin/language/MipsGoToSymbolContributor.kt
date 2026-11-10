package edu.missouristate.mars.plugin.language

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsSourceTimeline
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex.Companion.EXPANSION_SEED
import edu.missouristate.mars.plugin.language.index.MipsDeclarationSite
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import javax.swing.Icon

internal class MipsGoToSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        val written = HashSet<String>()
        val complete = FileBasedIndex.getInstance().processAllKeys(MipsDeclarationIndex.NAME, { name ->
            name == EXPANSION_SEED || processor.process(name.also(written::add))
        }, scope, filter)
        if (complete) {
            expandedSymbols(project, scope, filter).map(ExpandedSymbol::name)
                .filter(written::add).all(processor::process)
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val project = parameters.project
        val psiManager = PsiManager.getInstance(project)
        val analysis = project.service<MarsAnalysisService>()
        val candidates = ArrayList<Pair<VirtualFile, List<MipsDeclarationSite>>>()
        FileBasedIndex.getInstance().processValues(MipsDeclarationIndex.NAME, name, null, { file, sites ->
            candidates.add(file to sites)
            true
        }, parameters.searchScope, parameters.idFilter)
        // Expansion can query the include index, so leave the index callback before resolving PSI.
        for ((file, sites) in candidates) {
            val psi = psiManager.findFile(file) as MipsFile
            val timeline by lazy { analysis.editorTimeline(psi) }
            val declarations = sites.asSequence().mapNotNull {
                ProgressManager.checkCanceled()
                MipsDeclaration.findAt(psi, it.offset, name)
            }
            for (declaration in declarations) {
                val replaced = when (declaration.kind) {
                    SymbolKind.LABEL -> timeline.replacesLabel(psi, declaration.textOffset)
                    SymbolKind.EXTERNAL -> timeline.replacesExternal(psi, declaration.textOffset)
                    SymbolKind.MACRO -> timeline.localMacros(psi)[declaration]?.let { it.name != declaration.name } == true
                    else -> false
                }
                if (replaced) continue
                if (!processor.process(declaration)) return
            }
        }
        expandedSymbols(project, parameters.searchScope, parameters.idFilter).filter { it.name == name }
            .all { processor.process(ExpandedSymbolItem(it)) }
    }

    private fun expandedSymbols(project: Project, scope: GlobalSearchScope, filter: IdFilter?): Sequence<ExpandedSymbol> {
        val psi = PsiManager.getInstance(project)
        val analysis = project.service<MarsAnalysisService>()
        // A declaration can be supplied by an alias defined outside the requested result scope.
        val seeds = FileBasedIndex.getInstance().getContainingFiles(
            MipsDeclarationIndex.NAME, EXPANSION_SEED, GlobalSearchScope.allScope(project),
        )
        return seeds.asSequence().flatMap { seed ->
            ProgressManager.checkCanceled()
            mipsAssemblyRoots(psi.findFile(seed) as MipsFile).ifEmpty { listOf(seed) }
        }.distinct().flatMap { root ->
            mipsSourceTimeline(psi.findFile(root) as MipsFile).sourceFiles()
        }.distinct().filter { file ->
            val source = file.viewProvider.virtualFile
            scope.contains(source) && filter?.containsFileId(FileBasedIndex.getFileId(source)) != false
        }.flatMap { file ->
            ProgressManager.checkCanceled()
            analysis.editorTimeline(file).expandedSymbolsIn(file)
        }
    }

    private fun MipsSourceTimeline.expandedSymbolsIn(file: MipsFile): Sequence<ExpandedSymbol> = sequence {
        for (label in generatedLabelsIn(file)) {
            yield(ExpandedSymbol(label.name, file, label.site.textOffset, AllIcons.Nodes.Bookmark))
        }
        for (macro in localMacros(file).values) {
            if (macro.name != (macro.header as? MipsDeclaration)?.name) {
                yield(ExpandedSymbol(macro.name, file, macro.nameOffset, AllIcons.Nodes.Function))
            }
        }
        for (equivalence in generatedEquivalencesIn(file).values) {
            yield(ExpandedSymbol(equivalence.name, file, equivalence.site.textOffset, AllIcons.Nodes.Constant))
        }
        for (external in expandedExternalsIn(file).values) {
            yield(ExpandedSymbol(external.name, file, external.offset, AllIcons.Nodes.Field))
        }
    }
}

private data class ExpandedSymbol(val name: String, val file: MipsFile, val offset: Int, val icon: Icon)

private class ExpandedSymbolItem(symbol: ExpandedSymbol) :
    OpenFileDescriptor(symbol.file.project, symbol.file.viewProvider.virtualFile, symbol.offset), NavigationItem {
    private val symbolName = symbol.name
    private val symbolIcon = symbol.icon
    override fun getName() = symbolName
    override fun getPresentation() = PresentationData(symbolName, file.name, symbolIcon, null)
}
