package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.indexing.FileBasedIndex
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsReferenceIndex
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.psi.mipsIncludePaths
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

internal data class IndexedSymbolSite(val source: SourceId, val offset: Int, val macroOffset: Int)

internal data class IndexedSymbolCandidates(
    val declarations: List<IndexedSymbolSite>,
    val references: List<IndexedSymbolSite>,
)

/** The platform owns the project's service scope and cancels it on close or plugin unload. */
@Service(Service.Level.PROJECT)
class MarsAnalysisService(private val project: Project, scope: CoroutineScope) : Disposable {
    private val coordinator = AnalysisCoordinator(scope)

    val snapshot: PublishedAnalysis get() = coordinator.snapshot

    fun submit(contributions: List<IndexContribution>): Deferred<Boolean> = coordinator.submit(contributions)

    /** Reads the index only for files in the current physical include graph. */
    internal fun labelCompletionNames(root: MipsFile, position: PsiElement, prefix: String): Set<String> = buildSet {
        for (scope in labelScopes(root, macroOffset(position))) {
            for ((name, sites) in declarations(scope.file)) {
                ProgressManager.checkCanceled()
                if (name.startsWith(prefix) && sites.any { it.kind == SymbolKind.LABEL && visibleInMacro(it.macroOffset, scope.macro) }) {
                    add(name)
                }
            }
        }
    }

    internal fun labelTargets(root: MipsFile, position: PsiElement): List<MipsDeclaration> {
        val name = position.text
        val activeMacro = macroOffset(position)
        if (activeMacro != -1 && Macro.tokenIsMacroParameter(name, true)) return emptyList()
        val local = ArrayList<MipsDeclaration>()
        val outer = ArrayList<MipsDeclaration>()
        for (scope in labelScopes(root, activeMacro)) {
            val sites = declarations(scope.file)[name].orEmpty()
            for (site in sites) {
                ProgressManager.checkCanceled()
                if (site.kind != SymbolKind.LABEL || !visibleInMacro(site.macroOffset, scope.macro)) continue
                val matches = if (site.macroOffset != -1 || scope.inheritedMacro) local else outer
                matches.addAll(listOfNotNull(MipsDeclaration.findAt(scope.file, site.offset, name)))
            }
        }
        return local.ifEmpty { outer }
    }

    /** Finds the first physical .eqv in the expanded prefix of the current entry. */
    internal fun equivalenceTarget(root: MipsFile, position: PsiElement): MipsDeclaration? {
        if (DumbService.isDumb(project)) return null
        val name = position.text
        val visited = hashSetOf(root.viewProvider.virtualFile)
        val pending = ArrayDeque(listOf(equivalenceScope(root, name, position.textOffset)))
        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val scope = pending.last()
            if (scope.includes.hasNext()) {
                val (offset, path) = scope.includes.next()
                val target = resolveInclude(scope.file.findElementAt(offset) as MipsStringLiteral, path)
                if (target is MipsFile && visited.add(target.viewProvider.virtualFile)) {
                    pending.addLast(equivalenceScope(target, name, target.textLength))
                }
            } else {
                pending.removeLast()
                scope.declaration?.let { return it }
            }
        }
        return null
    }

    private fun equivalenceScope(file: MipsFile, name: String, limit: Int): EquivalenceScope {
        val declaration = declarations(file)[name].orEmpty().asSequence()
            .filter { it.kind == SymbolKind.CONSTANT && it.offset < limit }
            .mapNotNull { MipsDeclaration.findAt(file, it.offset, name) }
            .firstOrNull { it.node.findChildByType(MipsTypes.psi("REPLACEMENT")) != null }
        val before = declaration?.textOffset ?: limit
        // The shared include path cache preserves ascending physical offsets.
        val includes = mipsIncludePaths(file).entries.asSequence().takeWhile { it.key < before }.iterator()
        return EquivalenceScope(file, includes, declaration)
    }

    private data class EquivalenceScope(
        val file: MipsFile,
        val includes: Iterator<Map.Entry<Int, String>>,
        val declaration: MipsDeclaration?,
    )

    private data class LabelScope(val file: MipsFile, val macro: Int, val inheritedMacro: Boolean)

    private fun declarations(file: MipsFile) =
        FileBasedIndex.getInstance().getFileData(MipsDeclarationIndex.NAME, file.viewProvider.virtualFile, project)

    private fun labelScopes(root: MipsFile, activeMacro: Int): Sequence<LabelScope> = sequence {
        if (DumbService.isDumb(project)) return@sequence
        val pending = ArrayDeque(listOf(LabelScope(root, activeMacro, false)))
        val visited = HashSet<VirtualFile>()
        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val scope = pending.removeFirst()
            val file = scope.file
            if (!visited.add(file.viewProvider.virtualFile)) continue
            yield(scope)
            for ((offset, path) in mipsIncludePaths(file)) {
                ProgressManager.checkCanceled()
                val literal = file.findElementAt(offset) as MipsStringLiteral
                val owner = macroOffset(literal)
                if (visibleInMacro(owner, scope.macro)) {
                    val target = resolveInclude(literal, path)
                    if (target is MipsFile) pending.addLast(LabelScope(target, -1, scope.inheritedMacro || owner != -1))
                }
            }
        }
    }

    private fun resolveInclude(literal: MipsStringLiteral, path: String) = MipsFileReferenceSet(path, literal).resolve()

    private fun visibleInMacro(owner: Int, active: Int): Boolean = owner == -1 || owner == active

    private fun macroOffset(element: PsiElement): Int = PsiTreeUtil.findFirstParent(element, true) {
        PsiUtilCore.getElementType(it) == MipsTypes.psi("MACRO_DEFINITION")
    }?.textOffset ?: -1

    /** Waits for usable native indices and returns detached candidates for one scoped symbol key. */
    internal suspend fun lookupCandidates(key: SymbolKey, scope: GlobalSearchScope): IndexedSymbolCandidates {
        require(scope === GlobalSearchScope.EMPTY_SCOPE || scope.project === project) { "Expected a scope for this project" }
        return constrainedReadAction(ReadConstraint.inSmartMode(project), ReadConstraint.withDocumentsCommitted(project)) {
            val index = FileBasedIndex.getInstance()
            val declarations = buildList {
                index.processValues(MipsDeclarationIndex.NAME, key.name, null, { file, sites ->
                    val source = SourceId(file.url)
                    for (site in sites) {
                        ProgressManager.checkCanceled()
                        if (site.kind == key.kind) add(IndexedSymbolSite(source, site.offset, site.macroOffset))
                    }
                    true
                }, scope)
            }
            val references = buildList {
                index.processValues(MipsReferenceIndex.NAME, key.name, null, { file, sites ->
                    val source = SourceId(file.url)
                    for (site in sites) {
                        ProgressManager.checkCanceled()
                        if (site.kind == key.kind) add(IndexedSymbolSite(source, site.offset, site.macroOffset))
                    }
                    true
                }, scope)
            }
            IndexedSymbolCandidates(declarations, references)
        }
    }

    override fun dispose() = coordinator.close()
}
