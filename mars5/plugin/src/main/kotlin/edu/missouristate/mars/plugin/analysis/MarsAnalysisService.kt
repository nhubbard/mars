package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.containers.SLRUMap
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Kind
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsDeclarationSite
import edu.missouristate.mars.plugin.language.index.MipsReferenceIndex
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.psi.mipsIncludePaths
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
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
    private val lastBuild = AtomicReference<BuildSelection>()
    private val backgroundSymbols = SLRUMap<Path, AssemblySymbols>(0, 8)
    internal val backgroundAssembly = BackgroundAssemblyMemo()

    val snapshot: PublishedAnalysis get() = coordinator.snapshot

    fun submit(contributions: List<IndexContribution>): Deferred<Boolean> = coordinator.submit(contributions)

    internal fun selectedAssemblyEntry(file: MipsFile): Path? {
        val build = lastBuild.get() ?: return null
        return build.request.entry.takeIf { build.snapshot.sourcePath(file) != null }
    }

    internal class SymbolBuild(val entry: Path)

    private class BuildSelection(
        val request: SymbolBuild,
        val snapshot: AssemblySymbols = AssemblySymbols(request.entry),
    )

    internal fun beginSymbolBuild(entry: Path): SymbolBuild =
        SymbolBuild(entry.toAbsolutePath().normalize()).also { lastBuild.set(BuildSelection(it)) }

    internal fun publishBuiltSymbols(build: SymbolBuild, sources: CapturedAssemblySources, symbols: AssemblySymbolMap) {
        val current = lastBuild.get()
        if (current.request === build) {
            val snapshot = AssemblySymbols(build.entry, sources.revisions, symbols, sources.snapshot.texts())
            lastBuild.compareAndSet(current, BuildSelection(build, snapshot))
        }
    }

    /** The external pass has validated this snapshot under the read action in which it is published. */
    internal fun publishBackgroundSymbols(snapshot: AssemblySymbols) {
        synchronized(backgroundSymbols) { backgroundSymbols.put(snapshot.entry, snapshot) }
    }

    private fun <T : Any> fromAssemblySymbols(file: MipsFile, lookup: (AssemblySymbols, Path) -> T?): T? {
        val selected = lastBuild.get()?.snapshot?.let { snapshot ->
            snapshot.sourcePath(file)?.let { snapshot to it }
        }
        if (selected != null) lookup(selected.first, selected.second)?.let { return it }
        if (DumbService.isDumb(project)) return null
        val candidates = synchronized(backgroundSymbols) { backgroundSymbols.values() }
            .mapNotNull { snapshot -> snapshot.sourcePath(file)?.let { snapshot to it } }
        if (candidates.isEmpty()) return null
        val entry = selected?.first?.entry ?: inferredMipsAssemblyEntry(file)
        val candidate = candidates.firstOrNull { it.first.entry == entry } ?: return null
        return lookup(candidate.first, candidate.second)
    }

    /** Joins physical names with the last compatible assembly for this entry. */
    internal fun labelCompletionNames(root: MipsFile, position: PsiElement, prefix: String): Set<String> = buildSet {
        for (scope in labelScopes(root, macroOffset(position))) {
            for ((name, sites) in declarations(scope.file)) {
                ProgressManager.checkCanceled()
                if (name.startsWith(prefix) && sites.any { scope.hasLiteralLabel(name, it) }) {
                    add(name)
                }
            }
        }
        externalStorage(root).keys.filterTo(this) { it.startsWith(prefix) }
        addAll(builtLabelNames(root, position.textOffset + prefix.length, prefix))
    }

    internal fun labelTargets(root: MipsFile, position: PsiElement, allowExternal: Boolean): List<PsiElement> {
        val name = position.text
        val activeMacro = macroOffset(position)
        val local = ArrayList<MipsDeclaration>()
        val outer = ArrayList<MipsDeclaration>()
        for (scope in labelScopes(root, activeMacro)) {
            val sites = declarations(scope.file)[name].orEmpty()
            for (site in sites) {
                ProgressManager.checkCanceled()
                if (!scope.hasLiteralLabel(name, site)) continue
                val matches = if (scope.isMacroLocal(site)) local else outer
                matches.addAll(listOfNotNull(MipsDeclaration.findAt(scope.file, site.offset, name)))
            }
        }
        if (local.isNotEmpty()) return local
        if (outer.isNotEmpty()) return outer
        val built = builtLabelTarget(root, name, allowExternal)
        if (built != null) return listOf(built)
        return if (allowExternal) listOfNotNull(externalStorage(root)[name]) else emptyList()
    }

    private fun builtLabelTarget(root: MipsFile, name: String, allowExternal: Boolean): PsiElement? {
        return fromAssemblySymbols(root) { snapshot, _ -> builtLabelTarget(snapshot, name, allowExternal) }
    }

    private fun builtLabelTarget(build: AssemblySymbols, name: String, allowExternal: Boolean): PsiElement? {
        val definition = build.localSymbols[name] ?: build.symbols.globals()[name] ?: return null
        if (definition.kind() == Kind.EXTERNAL && !allowExternal) return null

        val location = definition.origin().location()
        var target: Document? = null
        val valid = build.revisions.validateSourceDocuments { path, document, revision ->
            if (path == location.file()) target = document
            document.modificationStamp == revision
        }
        if (!valid) return null
        val document = target ?: return null
        val manager = PsiDocumentManager.getInstance(project)
        if (!manager.isCommitted(document)) return null
        val file = manager.getPsiFile(document) as? MipsFile ?: return null
        val start = document.getLineStartOffset(location.line() - 1)
        val offset = CharArrayUtil.shiftForward(document.charsSequence, start, " \t,")
        return file.findElementAt(offset)
    }

    private fun builtLabelNames(root: MipsFile, offset: Int, prefix: String): List<String> =
        fromAssemblySymbols(root) { snapshot, source -> builtLabelNames(snapshot, source, offset, prefix) }.orEmpty()

    private fun builtLabelNames(build: AssemblySymbols, activeSource: Path, offset: Int, prefix: String): List<String>? {
        val names = build.matchingNames(prefix)
        if (names.isEmpty()) return null
        val valid = build.revisions.validateSourceDocuments { path, document, revision ->
            document.modificationStamp == revision ||
                path == activeSource && preservesBuiltLabelNames(build.texts.getValue(path), document, offset)
        }
        return names.takeIf { valid }
    }

    private fun externalStorage(root: MipsFile): Map<String, PsiElement> =
        if (DumbService.isDumb(project)) emptyMap() else editorTimeline(root).externalStorage

    internal fun macroTarget(root: MipsFile, position: PsiElement): PsiElement? {
        if (DumbService.isDumb(project)) return null
        val prefix = editorPrefix(root, position.textOffset)
        val tokens = expandedOperation(prefix, sourceLine(position, fromLineStart = true))
            ?.takeUnless(TokenList::isEmpty) ?: return null
        return matchingMacro(prefix, tokens)
    }

    internal fun expandedInstructionAlias(
        root: MipsFile, head: PsiElement, source: String, allowIncompleteOperand: Boolean = false,
    ): TokenList? = expandedOperationAlias(root, head, source, allowIncompleteOperand)
        ?.takeIf { it[0].type == TokenTypes.OPERATOR }

    internal fun expandedOperationAlias(
        root: MipsFile, head: PsiElement, source: String, allowIncompleteOperand: Boolean = false,
    ): TokenList? {
        if (DumbService.isDumb(project)) return null
        val prefix = editorPrefix(root, head.textOffset)
        if (prefix.equivalence(head.text) == null) return null
        return expandedOperation(prefix, source, allowIncompleteOperand)?.takeUnless(TokenList::isEmpty)
    }

    /** Unresolved operations may still become executable in a containing entry or macro expansion. */
    internal fun canSetOperationBreakpoint(root: MipsFile, head: PsiElement): Boolean {
        if (DumbService.isDumb(project)) return true
        val prefix = editorPrefix(root, head.textOffset)
        if (prefix.equivalence(head.text) == null) return true
        val tokens = expandedOperation(prefix, sourceLine(head, fromLineStart = true)) ?: return true
        if (tokens.isEmpty) return false
        return tokens[0].type == TokenTypes.OPERATOR || tokens[0].type == TokenTypes.IDENTIFIER
    }

    private fun expandedOperation(
        prefix: MipsSourceTimeline.Prefix, source: String, allowIncompleteOperand: Boolean = false,
    ): TokenList? {
        val errors = ErrorList()
        val tokens = Tokenizer.tokenizeWithEquivalences(
            source, errors, { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled,
        )
        // On a circular expansion the tokenizer restores the original source, so the token spans
        // cannot be used to highlight an operand. Other errors can be unfinished operand tokens.
        if (errors.errorsOccurred() && (!allowIncompleteOperand || tokens.processedLine == source)) return null
        tokens.discardTrailingComment()
        if (tokens.beginsWithLabel()) {
            tokens.remove(0)
            tokens.remove(0)
        }
        return tokens
    }

    private fun matchingMacro(prefix: MipsSourceTimeline.Prefix, tokens: TokenList): PsiElement? {
        val name = tokens[0].value
        val arity = tokens.size() - if (Macro.hasCallParentheses(tokens)) 3 else 1
        var target: PsiElement? = null
        val open = visitMacroPrefix(prefix.events) { definition ->
            if (definition.arity == arity && definition.name == name) target = definition.header
        }
        return target.takeIf { open == null }
    }

    /** A null list means no enclosing macro. An empty list means an undeclared parameter. */
    internal fun macroParameterTargets(root: MipsFile, position: PsiElement): List<PsiElement>? {
        if (DumbService.isDumb(project)) return null
        // A parameter on an aliased opening line is a formal, before that line opens the scope.
        val lineStart = CharArrayUtil.shiftBackwardUntil(root.viewProvider.contents, position.textOffset - 1, "\r\n") + 1
        val macro = visitMacroPrefix(editorPrefix(root, lineStart).events) {} ?: return null
        val name = position.text
        // The assembler uses the first exact match when a parameter name is repeated.
        val parameter = macro.parameters.firstOrNull { it.value == name } ?: return emptyList()
        // Expanded token columns need not correspond to a physical token in the header.
        if (macro.expanded) return listOf(macro.header)
        return listOfNotNull(macro.header.containingFile.findElementAt(macro.offset + parameter.startPos - 1))
    }

    private fun visitMacroPrefix(events: List<MipsSourceEvent>, onClosed: (MipsSourceEvent.Begin) -> Unit): MipsSourceEvent.Begin? {
        var open: MipsSourceEvent.Begin? = null
        for (event in events) {
            ProgressManager.checkCanceled()
            when (event) {
                is MipsSourceEvent.Begin -> open = open ?: event
                is MipsSourceEvent.End -> {
                    open?.let(onClosed)
                    open = null
                }
                else -> Unit
            }
        }
        return open
    }

    internal fun hasIntegerDataList(root: MipsFile, position: PsiElement): Boolean {
        if (DumbService.isDumb(project)) return false
        return editorPrefix(root, position.textOffset).hasIntegerDataList
    }

    /** An ambiguous physical operation may be a data directive or a continuation after substitution. */
    internal fun isIntegerDataCandidate(root: MipsFile, head: PsiElement, macroHead: Boolean): Boolean {
        val prefix = editorPrefix(root, head.textOffset)
        val tokens = expandedOperation(prefix, sourceLine(head, fromLineStart = true))
            ?.takeUnless(TokenList::isEmpty) ?: return false
        val integerOperands = (0 until tokens.size()).all { index ->
            val token = tokens[index]
            when (token.type) {
                TokenTypes.DIRECTIVE -> index == 0 && Directives.isIntegerDirective(Directives.matchDirective(token.value))
                TokenTypes.IDENTIFIER -> index != 0 || !token.value.startsWith('.')
                else -> TokenTypes.isIntegerTokenType(token.type)
            }
        }
        // A matched macro call takes precedence over a bare list in the assembler.
        return integerOperands && (macroHead || matchingMacro(prefix, tokens) == null)
    }

    internal fun isAliasedExportOperand(root: MipsFile, head: PsiElement, operand: PsiElement): Boolean {
        val source = root.viewProvider.contents.subSequence(head.textOffset, operand.textOffset).toString()
        val tokens = expandedOperationAlias(root, head, source) ?: return false
        return Directives.matchDirective(tokens[0].value) == Directives.GLOBL &&
            (1 until tokens.size()).all { tokens[it].type == TokenTypes.IDENTIFIER }
    }

    private fun editorPrefix(file: MipsFile, offset: Int): MipsSourceTimeline.Prefix =
        editorTimeline(file).prefix(file, offset)

    private fun editorTimeline(file: MipsFile): MipsSourceTimeline =
        selectedTimeline(file)?.takeIf { it.includes(file) } ?: mipsSourceTimeline(file)

    private fun selectedTimeline(file: MipsFile): MipsSourceTimeline? {
        val build = lastBuild.get()?.snapshot ?: return null
        val path = build.sourcePath(file) ?: return null
        if (path == build.entry) return null
        val entry = VirtualFileManager.getInstance().findFileByNioPath(build.entry) ?: return null
        val entryPsi = PsiManager.getInstance(project).findFile(entry) as? MipsFile ?: return null
        return mipsSourceTimeline(entryPsi)
    }

    /** Finds the first effective .eqv before the reference in the selected entry. */
    internal fun equivalenceTarget(root: MipsFile, position: PsiElement): PsiElement? {
        if (DumbService.isDumb(project)) return null
        val lineStart = CharArrayUtil.shiftBackwardUntil(root.viewProvider.contents, position.textOffset - 1, "\r\n") + 1
        return editorPrefix(root, lineStart).equivalence(position.text)?.site
    }

    private data class LabelScope(val file: MipsFile, val macro: Int, val inheritedMacro: Boolean)

    private fun LabelScope.isMacroLocal(site: MipsDeclarationSite) = site.macroOffset != -1 || inheritedMacro

    private fun LabelScope.hasLiteralLabel(name: String, site: MipsDeclarationSite): Boolean =
        site.kind == SymbolKind.LABEL && visibleInMacro(site.macroOffset, macro) &&
            !(isMacroLocal(site) && Macro.tokenIsMacroParameter(name, true))

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
                    val target = resolveMipsInclude(literal, path)
                    if (target is MipsFile) pending.addLast(LabelScope(target, -1, scope.inheritedMacro || owner != -1))
                }
            }
        }
    }

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

    override fun dispose() {
        coordinator.close()
        backgroundAssembly.clear()
        synchronized(backgroundSymbols) { backgroundSymbols.clear() }
    }
}
