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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsSourceTimeline
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.analysis.assemblySourceSyntax
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
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
            contextualSymbols(project, scope, filter).map(ContextualSymbol::name)
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
                if (timeline.replacesDeclaration(psi, declaration)) continue
                if (!processor.process(declaration)) return
            }
        }
        contextualSymbols(project, parameters.searchScope, parameters.idFilter).filter { it.name == name }
            .all { processor.process(ContextualSymbolItem(it)) }
    }

    private fun contextualSymbols(project: Project, scope: GlobalSearchScope, filter: IdFilter?): Sequence<ContextualSymbol> {
        val psi = PsiManager.getInstance(project)
        val analysis = project.service<MarsAnalysisService>()
        // A declaration can be supplied by an alias defined outside the requested result scope.
        val seeds = FileBasedIndex.getInstance().getContainingFiles(
            MipsDeclarationIndex.NAME, EXPANSION_SEED, GlobalSearchScope.allScope(project),
        )
        val indexedRoots = seeds.asSequence().flatMap { seed ->
            ProgressManager.checkCanceled()
            mipsAssemblyRoots(psi.findFile(seed) as MipsFile).ifEmpty { listOf(seed) }
        }
        val settings = project.service<MarsAssemblySettings>()
        val files = VirtualFileManager.getInstance()
        val configuredRoots = (listOfNotNull(settings.exceptionHandler) + settings.projectSources)
            .asSequence().mapNotNull(files::findFileByNioPath)
        val roots = (indexedRoots + configuredRoots).distinct()
            .onEach { ProgressManager.checkCanceled() }
            .mapNotNull(psi::findFile)
            .mapNotNull(::assemblySourceSyntax)
        fun inScope(file: MipsFile): Boolean {
            val source = file.originalFile.viewProvider.virtualFile
            return scope.contains(source) && filter?.containsFileId(FileBasedIndex.getFileId(source)) != false
        }
        return sequence {
            val typedFiles = HashSet<MipsFile>()
            val unindexedSymbols = HashSet<ContextualSymbol>()
            for (root in roots) {
                val timeline = mipsSourceTimeline(root)
                for (file in timeline.sourceFiles()) {
                    ProgressManager.checkCanceled()
                    if (typedFiles.add(file) && inScope(file)) yieldAll(analysis.editorTimeline(file).expandedSymbolsIn(file))
                }
                for (file in timeline.unindexedSourceFiles()) {
                    ProgressManager.checkCanceled()
                    if (inScope(file)) yieldAll(timeline.unindexedSymbolsIn(file).filter(unindexedSymbols::add))
                }
            }
        }
    }

    private fun MipsSourceTimeline.replacesDeclaration(file: MipsFile, declaration: MipsDeclaration): Boolean =
        when (declaration.kind) {
            SymbolKind.LABEL -> replacesLabel(file, declaration.textOffset)
            SymbolKind.EXTERNAL -> replacesExternal(file, declaration.textOffset)
            SymbolKind.MACRO -> localMacros(file)[declaration]?.let { it.name != declaration.name } == true
            else -> false
        }

    private fun MipsSourceTimeline.unindexedSymbolsIn(file: MipsFile): Sequence<ContextualSymbol> = sequence {
        val declarations = CachedValuesManager.getCachedValue(file) {
            val written = buildList {
                for (element in SyntaxTraverser.psiTraverser(file)) {
                    ProgressManager.checkCanceled()
                    if (element is MipsDeclaration) add(element)
                }
            }
            CachedValueProvider.Result.create(written, file)
        }
        val source = file.originalFile
        for (declaration in declarations) {
            ProgressManager.checkCanceled()
            val name = declaration.name ?: continue
            if (replacesDeclaration(file, declaration)) continue
            yield(ContextualSymbol(name, source, declaration.textOffset, declaration.presentation.getIcon(false)))
        }
        yieldAll(expandedSymbolsIn(file))
    }

    private fun MipsSourceTimeline.expandedSymbolsIn(file: MipsFile): Sequence<ContextualSymbol> = sequence {
        val source = file.originalFile
        for ((name, site) in generatedLabelsIn(file)) {
            yield(ContextualSymbol(name, source, site.textOffset, AllIcons.Nodes.Bookmark))
        }
        for ((header, name, offset) in localMacros(file).values) {
            if (name != (header as? MipsDeclaration)?.name) {
                yield(ContextualSymbol(name, source, offset, AllIcons.Nodes.Function))
            }
        }
        for ((site, name) in generatedEquivalencesIn(file).values) {
            yield(ContextualSymbol(name, source, site.textOffset, AllIcons.Nodes.Constant))
        }
        for (external in expandedExternalsIn(file).values) {
            yield(ContextualSymbol(external.name, source, external.offset, AllIcons.Nodes.Field))
        }
    }
}

private data class ContextualSymbol(val name: String, val file: PsiFile, val offset: Int, val icon: Icon?)

private class ContextualSymbolItem(symbol: ContextualSymbol) :
    OpenFileDescriptor(symbol.file.project, symbol.file.viewProvider.virtualFile, symbol.offset), NavigationItem {
    private val symbolName = symbol.name
    private val symbolIcon = symbol.icon
    override fun getName() = symbolName
    override fun getPresentation() = PresentationData(symbolName, file.name, symbolIcon, null)
}
