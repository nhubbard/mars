package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.elementType
import com.intellij.util.containers.SLRUMap
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Kind
import edu.missouristate.mars.api.AssemblySymbolMap.MacroCall
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsAssemblyMode
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsDeclarationSite
import edu.missouristate.mars.plugin.language.index.MipsReferenceIndex
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.operationOwner
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsIncludedDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsMacroFormal
import edu.missouristate.mars.plugin.language.psi.equivalenceDefinitionParameterContexts
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

internal data class IndexedSymbolSite(val source: SourceId, val offset: Int, val macroOffset: Int)

internal data class OperationSite(
    val name: String, val type: TokenTypes, val range: TextRange, val compositeEquivalence: Boolean = false,
    val macroDefinition: MipsSourceEvent.Begin? = null,
)

internal enum class OperandParenthesis { ADDRESS, GROUP }

internal data class FormattingLine(
    val operationType: TokenTypes?,
    val atMargin: Boolean,
    // Null means the formatter has no complete expanded operand layout.
    val operandParentheses: Map<Int, OperandParenthesis>?,
)

internal data class MacroInvocation(
    val tokens: TokenList, val fullTokens: TokenList?, val parameters: List<List<String>>,
)

internal data class NestedMacroInvocation(val calls: List<MacroCall>, val argument: Int)

internal data class MacroArityMismatch(val site: PsiElement, val name: String, val actual: Int, val expected: List<Int>)

private val mnemonicType = MipsTypes.psi("MNEMONIC")
private val addressExpressionType = MipsTypes.psi("ADDRESS_EXPR")
private val groupExpressionType = MipsTypes.psi("GROUP_EXPR")
private val leftParenType = MipsTypes.psi("LEFT_PAREN")
private val incompleteOperandTypes = setOf(TokenType.ERROR_ELEMENT, MipsTypes.psi("OPERAND_FRAGMENT"),
    MipsTypes.psi("UNCLOSED_STRING"))

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

    internal fun selectedAssembly(file: MipsFile): AssemblySelection? {
        val virtualFile = file.viewProvider.virtualFile
        if (!virtualFile.isInLocalFileSystem) return null
        val path = virtualFile.toNioPath()
        val build = lastBuild.get()
        if (build != null) {
            val selection = build.request.selection
            if (selection.sourceSet.mode != MarsAssemblyMode.PROJECT &&
                (build.snapshot.sourcePath(file) != null || path == exceptionHandler ||
                    selection.sourceSet.mode == MarsAssemblyMode.DIRECTORY && virtualFile.isAssemblyDirectorySource() && path.parent == selection.entry.parent)
            ) return selection.forEditor()
        }
        val sources = settings.projectSourceSet() ?: return null
        val lastProject = build?.takeIf { it.request.selection.sourceSet == sources }
        if (path in sources.entries || lastProject?.snapshot?.sourcePath(file) != null ||
            !DumbService.isDumb(project) && mipsAssemblyRoots(file).any { it.toNioPath() in sources.entries }
        ) {
            return lastProject?.request?.selection?.forEditor() ?: AssemblySelection(sources, settings.executionOptions)
        }
        return null
    }

    private val settings get() = project.service<MarsAssemblySettings>()
    private val exceptionHandler get() = settings.exceptionHandler

    internal data class AssemblySelection(val sourceSet: MarsSourceSet, val options: MarsExecutionOptions) {
        val entry get() = sourceSet.entry
    }

    internal class SymbolBuild(val selection: AssemblySelection) {
        val entry: Path get() = selection.entry
    }

    private class BuildSelection(
        val request: SymbolBuild,
        val snapshot: AssemblySymbols = AssemblySymbols(request.entry, options = request.selection.options),
    )

    // A launch can override the assembler policy without changing editor diagnostics for the project.
    private fun AssemblySelection.forEditor(): AssemblySelection {
        val defaults = settings.executionOptions
        return copy(options = options.copy(
            allowPseudoInstructions = defaults.allowPseudoInstructions,
            warningsAreErrors = defaults.warningsAreErrors,
            memoryLayout = defaults.memoryLayout,
        ))
    }

    internal fun beginSymbolBuild(entry: Path, options: MarsExecutionOptions = settings.executionOptions): SymbolBuild =
        beginSymbolBuild(MarsSourceSet(entry), options)

    internal fun beginSymbolBuild(sourceSet: MarsSourceSet, options: MarsExecutionOptions): SymbolBuild =
        SymbolBuild(AssemblySelection(sourceSet, options))
        .also { lastBuild.set(BuildSelection(it)) }

    internal fun publishBuiltSymbols(build: SymbolBuild, sources: CapturedAssemblySources, symbols: AssemblySymbolMap) {
        val current = lastBuild.get()
        if (current.request === build) {
            val snapshot = AssemblySymbols(build.entry, sources.revisions, symbols, sources.snapshot.texts(), build.selection.options,
                sources.rootsBySource, sources.directoryStamp, sources.exceptionHandler, sources.sourceSet)
            lastBuild.compareAndSet(current, BuildSelection(build, snapshot))
        }
    }

    /** The external pass has validated this snapshot under the read action in which it is published. */
    internal fun publishBackgroundSymbols(snapshot: AssemblySymbols) {
        synchronized(backgroundSymbols) { backgroundSymbols.put(snapshot.entry, snapshot) }
    }

    private fun <T : Any> fromAssemblySymbols(file: MipsFile, lookup: (AssemblySymbols, Path) -> T?): T? {
        val handler = exceptionHandler
        val selected = lastBuild.get()?.takeIf { it.request.selection.options == it.request.selection.forEditor().options }
            ?.snapshot?.takeIf { it.isCurrent(handler) }?.let { snapshot ->
                snapshot.sourcePath(file)?.let { snapshot to it }
            }
        if (selected != null && selected.first.hasCurrentDirectory()) lookup(selected.first, selected.second)?.let { return it }
        if (DumbService.isDumb(project)) return null
        val candidates = synchronized(backgroundSymbols) { backgroundSymbols.values() }
            .mapNotNull { snapshot -> snapshot.sourcePath(file)?.let { snapshot to it } }
        if (candidates.isEmpty()) return null
        val configuration = selectedAssembly(file)
        val sources = configuration?.sourceSet ?: inferredMipsAssemblyEntry(file)?.let { MarsSourceSet(it) }
        val options = configuration?.options ?: settings.executionOptions
        val candidate = candidates.firstOrNull {
            it.first.sourceSet == sources && it.first.options == options &&
                it.first.hasCurrentDirectory() && it.first.exceptionHandler == handler
        } ?: return null
        return lookup(candidate.first, candidate.second)
    }

    /** Joins physical and equivalence-generated names with the last compatible assembly. */
    internal fun labelCompletionNames(root: MipsFile, position: PsiElement, prefix: String): Set<String> = buildSet {
        if (!DumbService.isDumb(project)) {
            val timeline = editorTimeline(root)
            val macro = timeline.macroAt(root, position.textOffset)
            for (file in timeline.sourceFiles()) {
                for ((name, sites) in declarations(file)) {
                    ProgressManager.checkCanceled()
                    if (name.startsWith(prefix) && sites.any {
                        it.isLiteralLabel(file, timeline) && visibleLabel(name, timeline.macroAt(file, it.offset), macro)
                    }) add(name)
                }
            }
            for ((name, labels) in timeline.generatedLabels) {
                ProgressManager.checkCanceled()
                if (name.startsWith(prefix) && labels.any { it.macro == null || it.macro === macro }) add(name)
            }
            timeline.externalStorage.keys.filterTo(this) { it.startsWith(prefix) }
        }
        addAll(builtLabelNames(root, position.textOffset + prefix.length, prefix))
    }

    internal fun labelTargets(root: MipsFile, position: PsiElement, allowExternal: Boolean): List<PsiElement> {
        val name = position.text
        val timeline = if (DumbService.isDumb(project)) null else editorTimeline(root)
        if (timeline != null) {
            val local = ArrayList<PsiElement>()
            val outer = ArrayList<PsiElement>()
            val macro = timeline.macroAt(root, position.textOffset)
            for (file in timeline.sourceFiles()) {
                for (site in declarations(file)[name].orEmpty()) {
                    ProgressManager.checkCanceled()
                    if (!site.isLiteralLabel(file, timeline)) continue
                    val owner = timeline.macroAt(file, site.offset)
                    if (!visibleLabel(name, owner, macro)) continue
                    val matches = if (owner == null) outer else local
                    matches.addAll(listOfNotNull(MipsDeclaration.findAt(file, site.offset, name)))
                }
            }
            for (label in timeline.generatedLabels[name].orEmpty()) {
                ProgressManager.checkCanceled()
                if (label.macro == null) outer.add(label.site)
                else if (label.macro === macro) local.add(label.site)
            }
            if (local.isNotEmpty()) return local
            if (outer.isNotEmpty()) return outer
        }
        val built = builtLabelTarget(root, name, allowExternal)
        if (built != null) return listOf(built)
        return if (allowExternal) listOfNotNull(timeline?.externalStorage?.get(name)) else emptyList()
    }

    private fun builtLabelTarget(root: MipsFile, name: String, allowExternal: Boolean): PsiElement? {
        return fromAssemblySymbols(root) { snapshot, source -> builtLabelTarget(snapshot, source, name, allowExternal) }
    }

    private fun builtLabelTarget(build: AssemblySymbols, source: Path, name: String, allowExternal: Boolean): PsiElement? {
        val definition = build.localSymbols(source)[name] ?: build.symbols.globals()[name] ?: return null
        if (definition.kind() == Kind.EXTERNAL && !allowExternal) return null

        val location = definition.origin()?.location() ?: return null
        var target: Document? = null
        val valid = build.revisions.validateSourceDocuments { path, document, revision ->
            if (path == location.file()) target = document
            document.modificationStamp == revision
        }
        if (!valid) return null
        val document = target ?: return null
        val manager = PsiDocumentManager.getInstance(project)
        if (!manager.isCommitted(document)) return null
        val file = manager.getPsiFile(document) ?: return null
        val start = document.getLineStartOffset(location.line() - 1)
        val offset = definition.column()?.let { start + it - 1 }
            ?: CharArrayUtil.shiftForward(document.charsSequence, start, " \t,")
        return if (file is MipsFile) file.findElementAt(offset) else MipsIncludedDeclaration.at(file, offset)
    }

    private fun builtLabelNames(root: MipsFile, offset: Int, prefix: String): List<String> =
        fromAssemblySymbols(root) { snapshot, source -> builtLabelNames(snapshot, source, offset, prefix) }.orEmpty()

    private fun builtLabelNames(build: AssemblySymbols, activeSource: Path, offset: Int, prefix: String): List<String>? {
        val names = build.matchingNames(activeSource, prefix)
        if (names.isEmpty()) return null
        val valid = build.revisions.validateSourceDocuments { path, document, revision ->
            document.modificationStamp == revision ||
                path == activeSource && preservesBuiltLabelNames(build.texts.getValue(path), document, offset)
        }
        return names.takeIf { valid }
    }

    internal fun macroTargets(root: MipsFile, position: PsiElement): List<PsiElement> {
        val owner = operationOwner(position) ?: return emptyList()
        val site = operationSite(root, owner.operationHead)
        val definition = site?.macroDefinition?.takeIf { site.range.startOffset == position.textOffset }
        if (definition == null || editorTimeline(root).hasIncompleteIncludes) {
            val calls = assembledMacroCallsAt(root, position)
            if (calls.isNotEmpty()) return calls.map(MacroCall::definition).distinct().mapNotNull(::macroDeclarationAt)
        }
        val header = definition?.header ?: return emptyList()
        return listOfNotNull(header as? MipsDeclaration ?: header.containingFile.findElementAt(definition.nameOffset))
    }

    internal fun nestedMacroCallsAt(root: MipsFile, position: PsiElement): Set<MacroCall> {
        if (macroBodyPrefix(root, position)?.openMacro == null) return emptySet()
        return assembledMacroCallsAt(root, position)
    }

    private fun assembledMacroCallsAt(root: MipsFile, position: PsiElement): Set<MacroCall> {
        return fromAssemblySymbols(root) { snapshot, path ->
            val calls = snapshot.symbols.macroCalls()[sourceTokenAt(root, path, position.textOffset)]
                ?: return@fromAssemblySymbols null
            if (!snapshot.hasCurrentMacroSources()) return@fromAssemblySymbols null
            calls
        }.orEmpty()
    }

    internal fun assembledMacroUsages(target: MipsDeclaration): Sequence<SourceToken> {
        val file = target.containingFile as MipsFile
        val snapshots = listOfNotNull(lastBuild.get()?.snapshot) + synchronized(backgroundSymbols) { backgroundSymbols.values() }
        val handler = exceptionHandler
        return snapshots.asSequence().flatMap { snapshot ->
            ProgressManager.checkCanceled()
            val path = snapshot.sourcePath(file) ?: return@flatMap emptySequence()
            val sites = snapshot.assembledMacroUsages[sourceTokenAt(file, path, target.textOffset)] ?: return@flatMap emptySequence()
            val selection = AssemblySelection(snapshot.sourceSet, snapshot.options)
            if (!snapshot.isCurrent(handler) || !snapshot.hasCurrentDirectory() ||
                selection.options != selection.forEditor().options || !snapshot.hasCurrentMacroSources()
            ) return@flatMap emptySequence()
            sites.asSequence()
        }.distinct()
    }

    private fun sourceTokenAt(file: MipsFile, path: Path, offset: Int): SourceToken {
        val document = file.fileDocument
        val line = document.getLineNumber(offset)
        return SourceToken(Location(path, line + 1), offset - document.getLineStartOffset(line) + 1)
    }

    private fun AssemblySymbols.hasCurrentMacroSources(): Boolean {
        val manager = PsiDocumentManager.getInstance(project)
        return revisions.validateSourceDocuments { _, source, revision ->
            manager.isCommitted(source) && source.modificationStamp == revision
        }
    }

    private fun macroDeclarationAt(target: SourceToken): PsiElement? {
        val (file, offset) = sourceTokenPosition(target) ?: return null
        if (file !is MipsFile) return MipsIncludedDeclaration.at(file, offset)
        val token = file.findElementAt(offset)!!
        val declaration = token.parent
        return if (declaration.elementType == MipsTypes.psi("MACRO_SIGNATURE")) declaration else token
    }

    internal fun sourceTokenPosition(target: SourceToken): Pair<PsiFile, Int>? {
        val location = target.location()
        // The source snapshot has already validated each physical file and its committed document.
        val source = VirtualFileManager.getInstance().findFileByNioPath(location.file())!!
        val file = PsiManager.getInstance(project).findFile(source) ?: return null
        val line = location.line() - 1
        return file to (file.fileDocument.getLineStartOffset(line) + target.column() - 1)
    }

    internal fun macroCompletionSignatures(root: MipsFile, offset: Int, namePrefix: String): Map<String, List<String>> {
        if (DumbService.isDumb(project)) return emptyMap()
        val prefix = editorPrefix(root, offset)
        return prefix.macroOverloads { it.startsWith(namePrefix) && prefix.equivalence(it) == null }
            .groupBy(MipsSourceEvent.Begin::name) { definition ->
                definition.parameters.joinToString(prefix = "(", postfix = ")") { it.value }
            }
    }

    internal fun macroInvocation(root: MipsFile, head: PsiElement, source: String): MacroInvocation? {
        if (DumbService.isDumb(project)) return null
        val prefix = editorPrefix(root, head.textOffset)
        if (prefix.openMacro != null) return null
        val tokens = expandedOperation(prefix, writtenOperationPrefix(head, source), allowIncompleteOperand = true)
            ?.takeUnless(TokenList::isEmpty) ?: return null
        val definitions = prefix.macroOverloads(tokens[0].value)
        if (definitions.isEmpty()) return null
        val fullTokens = expandedOperation(prefix, sourceLine(head, fromLineStart = true), allowIncompleteOperand = true)
        return MacroInvocation(tokens, fullTokens, definitions.values.map { definition -> definition.parameters.map { it.value } })
    }

    internal fun nestedMacroInvocation(root: MipsFile, head: PsiElement, caret: Int): NestedMacroInvocation? {
        if (macroBodyPrefix(root, head)?.openMacro == null) return null
        return fromAssemblySymbols(root) { snapshot, path ->
            val document = root.fileDocument
            val line = document.getLineNumber(head.textOffset)
            val start = document.getLineStartOffset(line)
            val column = caret - start + 1
            val site = snapshot.macroCallSites[Location(path, line + 1)].orEmpty()
                .filter { it.key.column() <= column }.maxByOrNull { it.key.column() }
                ?: return@fromAssemblySymbols null
            if (!snapshot.hasCurrentMacroSources()) return@fromAssemblySymbols null
            val previous = root.findElementAt(caret - 1).elementType
            val afterSeparator = previous == TokenType.WHITE_SPACE || previous == MipsTypes.psi("COMMA")
            val active = site.value.filter { call ->
                call.closingParenthesisColumn()?.let {
                    caret < root.findElementAt(start + it - 1)!!.textRange.endOffset
                } ?: true
            }
            val arguments = active.map { call ->
                val columns = call.argumentColumns()
                val index = columns.indexOfLast { it <= column }
                when {
                    index < 0 -> 0
                    // The editor can split a signed literal into several leaves.
                    column > columns[index] && afterSeparator -> index + 1
                    columns.getOrNull(index - 1) == columns[index] -> -1
                    else -> index
                }
            }
            NestedMacroInvocation(active, arguments.distinct().singleOrNull() ?: -1)
        }
    }

    internal fun macroArityMismatch(root: MipsFile, timeline: MipsSourceTimeline, head: PsiElement): MacroArityMismatch? {
        val start = sourceLineStart(head)
        val prefix = timeline.prefix(root, start)
        // Calls in a macro body are resolved when the body expands, in the invocation's scope.
        if (prefix.openMacro != null) return null
        val replacement: (String) -> String? = { prefix.equivalence(it)?.replacement }
        if (head.text !in timeline.macroNames && !hasLeadingEquivalence(head, replacement)) {
            val first = CharArrayUtil.shiftForward(root.viewProvider.contents, start, " \t,")
            if (replacement(root.findElementAt(first)!!.text) == null) return null
        }
        val errors = ErrorList()
        val expanded = Tokenizer.tokenizeMappedLine(
            sourceLine(head, fromLineStart = true), errors, replacement, ProgressManager::checkCanceled,
        )
        if (errors.errorsOccurred()) return null
        val tokens = expanded.tokens().operationTokens()
        if (tokens.isEmpty) return null
        val name = tokens[0].value
        val definitions = prefix.macroOverloads(name)
        if (definitions.isEmpty()) return null
        val actual = tokens.macroArgumentCount()
        if (actual in definitions) return null
        val offset = start + expanded.originalOffset(tokens[0].startPos - 1)
        return MacroArityMismatch(root.findElementAt(offset)!!, name, actual, definitions.keys.sorted())
    }

    internal fun editorInstructionTokens(
        root: MipsFile, head: PsiElement, source: String, allowIncompleteOperand: Boolean = false,
    ): TokenList? {
        val prefix = if (head.elementType != mnemonicType || isGeneratedMnemonicLabel(root, head)) {
            leadingEquivalencePrefix(root, head) ?: return null
        } else {
            val written = Tokenizer().tokenizeLine(1, source, ErrorList(), false).also { it.processedLine = source }
            val operands = 1 until written.size()
            if (operands.none { written[it].type == TokenTypes.IDENTIFIER } || DumbService.isDumb(project)) return written
            val visible = editorPrefix(root, sourceLineStart(head))
            // Only identifiers can be equivalence names. Unbound operands keep their written spans.
            if (operands.none { visible.equivalence(written[it].value) != null }) return written
            visible
        }
        return expandedOperation(prefix, writtenOperationPrefix(head, source), allowIncompleteOperand)
            ?.takeIf { !it.isEmpty && it[0].type == TokenTypes.OPERATOR }
    }

    private fun isGeneratedMnemonicLabel(root: MipsFile, head: PsiElement): Boolean {
        if (DumbService.isDumb(project) || followingEquivalenceName(head) == null) return false
        val original = root.findElementAt(head.textOffset)
        return editorTimeline(root).generatedLabels[head.text].orEmpty().any { it.site == original }
    }

    private fun expandedOperationAlias(root: MipsFile, head: PsiElement, source: String): TokenList? {
        val prefix = leadingEquivalencePrefix(root, head) ?: return null
        return expandedOperation(prefix, writtenOperationPrefix(head, source))
            ?.takeUnless(TokenList::isEmpty)
    }

    /** An equivalence can put the mnemonic at the head itself or after a generated label. */
    internal fun operationSite(root: MipsFile, head: PsiElement): OperationSite? {
        val prefix = leadingEquivalencePrefix(root, head)
        if (prefix != null) return expandedOperationSite(root, head, prefix)
        if (head.elementType == mnemonicType) return OperationSite(head.text, TokenTypes.OPERATOR, head.textRange)
        if (!DumbService.isDumb(project)) {
            val timeline = editorTimeline(root)
            if (Macro.tokenIsMacroParameter(head.text, true) && timeline.macroAt(root, sourceLineStart(head)) != null) {
                return OperationSite(head.text, TokenTypes.MACRO_PARAMETER, head.textRange)
            }
            if (head.text in timeline.macroNames) {
                return expandedOperationSite(root, head, timeline.prefix(root, head.textOffset))
            }
        }
        return null
    }

    private fun expandedOperationSite(
        root: MipsFile, head: PsiElement, prefix: MipsSourceTimeline.Prefix,
    ): OperationSite? {
        val start = sourceLineStart(head)
        val source = sourceLine(head, fromLineStart = true)
        val errors = ErrorList()
        val expansion = Tokenizer.tokenizeMappedLine(
            source, errors, { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled,
        )
        val tokens = expansion.tokens()
        tokens.discardTrailingComment()
        val operationIndex = if (tokens.beginsWithLabel()) 2 else 0
        if (operationIndex == tokens.size()) return null
        val operation = tokens[operationIndex]
        val type = prefix.operationType(tokens, operationIndex) ?: return null
        val written = expansion.originalOffset(operation.startPos - 1)
        val composite = (0 until tokens.size()).any {
            it != operationIndex && expansion.originalOffset(tokens[it].startPos - 1) == written
        }
        val macro = if (type == TokenTypes.IDENTIFIER && !errors.errorsOccurred()) {
            matchingMacro(prefix, tokens, operationIndex)
        } else null
        return OperationSite(operation.value, type, root.findElementAt(start + written)!!.textRange, composite, macro)
    }

    internal fun formattingLine(
        root: MipsFile, anchor: PsiElement, timeline: MipsSourceTimeline, projectOperands: Boolean,
    ): FormattingLine? {
        val start = sourceLineStart(anchor)
        val prefix = timeline.prefix(root, start)
        val source = sourceLine(anchor, fromLineStart = true)
        val errors = ErrorList()
        val replacement: (String) -> String? = { prefix.equivalence(it)?.replacement }
        val mapped = if (projectOperands) Tokenizer.tokenizeMappedLine(source, errors, replacement, ProgressManager::checkCanceled) else null
        val expanded = mapped?.expanded() ?: Tokenizer.expandLine(source, errors, replacement, ProgressManager::checkCanceled)
        if (errors.errorsOccurred()) return null
        val hasLabel = expanded.tokens().beginsWithLabel()
        val tokens = expanded.tokens().operationTokens()
        val type = if (tokens.isEmpty) null else prefix.operationType(tokens, 0)
        // Even a macro call can record an equivalence as a tokenizer side effect.
        val canFormatOperands = expanded.definition() == null
        val spacingType = type.takeIf { canFormatOperands }
        val openings = if (canFormatOperands) mapped?.let { expandedOperandParentheses(it, source, start) } else null
        return FormattingLine(spacingType, hasLabel || type == TokenTypes.DIRECTIVE, openings)
    }

    private fun expandedOperandParentheses(
        mapped: Tokenizer.MappedLine, source: String, start: Int,
    ): Map<Int, OperandParenthesis>? {
        val expanded = mapped.tokens().processedLine
        if (expanded == source) return null
        val file = PsiFileFactory.getInstance(project).createFileFromText("expanded.asm", MipsFileType, expanded)
        val tree = SyntaxTraverser.astTraverser(file.node)
        if (tree.filterTypes { it in incompleteOperandTypes }.firstOrNull() != null) return null
        return tree.asSequence().mapNotNull { node ->
            val role = when (node.elementType) {
                addressExpressionType -> OperandParenthesis.ADDRESS
                groupExpressionType -> OperandParenthesis.GROUP
                else -> return@mapNotNull null
            }
            val opening = node.findChildByType(leftParenType)!!
            (start + mapped.originalOffset(opening.startOffset)) to role
        }.toMap()
    }

    internal fun isGeneratedLabel(root: MipsFile, element: PsiElement): Boolean {
        if (DumbService.isDumb(project)) return false
        return element in editorTimeline(root).generatedLabelSites
    }

    /** Unresolved operations may still become executable in a containing entry or macro expansion. */
    internal fun canSetOperationBreakpoint(root: MipsFile, head: PsiElement): Boolean {
        val prefix = leadingEquivalencePrefix(root, head) ?: return true
        val tokens = expandedOperation(prefix, sourceLine(head, fromLineStart = true)) ?: return true
        if (tokens.isEmpty) return false
        val operation = tokens[0]
        return operation.type == TokenTypes.OPERATOR || operation.type == TokenTypes.IDENTIFIER ||
            prefix.isMacroParameter(operation.value)
    }

    private fun leadingEquivalencePrefix(root: MipsFile, head: PsiElement): MipsSourceTimeline.Prefix? {
        if (DumbService.isDumb(project)) return null
        val following = followingEquivalenceName(head)
        if (following == null && head.elementType == mnemonicType) return null
        val prefix = editorPrefix(root, head.textOffset)
        if (prefix.equivalence(head.text) != null) return prefix
        return prefix.takeIf { following != null && it.equivalence(following) != null }
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
        return tokens.operationTokens()
    }

    // Tokenize the written label too, so operationTokens removes the same label as assembly.
    private fun writtenOperationPrefix(head: PsiElement, suffix: String): String {
        val text = head.containingFile.viewProvider.contents
        val start = sourceLineStart(text, head.textOffset)
        return buildString {
            append(text, start, head.textOffset)
            append(suffix)
        }
    }

    private fun TokenList.operationTokens(): TokenList {
        discardTrailingComment()
        if (beginsWithLabel()) {
            remove(0)
            remove(0)
        }
        return this
    }

    private fun matchingMacro(
        prefix: MipsSourceTimeline.Prefix, tokens: TokenList, operationIndex: Int = 0,
    ): MipsSourceEvent.Begin? {
        if (prefix.openMacro != null) return null
        return prefix.matchingMacro(tokens, operationIndex)
    }

    /** A null list means no enclosing macro. An empty list means an undeclared parameter. */
    internal fun macroParameterTargets(root: MipsFile, position: PsiElement): List<PsiElement>? {
        val macro = macroBodyPrefix(root, position)?.openMacro ?: return null
        val name = position.text
        // The assembler uses the first exact match when a parameter name is repeated.
        val parameter = macro.parametersByName[name] ?: return emptyList()
        return listOfNotNull(macro.header.containingFile.findElementAt(parameter.offset))
            .map { (it.parent as? MipsMacroFormal) ?: it }
    }

    internal fun macroFormalNames(root: MipsFile, formal: MipsMacroFormal): Set<String> {
        if (DumbService.isDumb(project)) return emptySet()
        val macro = editorTimeline(root).localMacros(root)[formal.parent.parent] ?: return emptySet()
        return macro.parameters.filter { it.offset == formal.textOffset }.mapTo(LinkedHashSet(), MipsSourceEvent.MacroFormal::value)
    }

    internal fun macroParameterNames(root: MipsFile, position: PsiElement): List<String> {
        val prefix = macroBodyPrefix(root, position) ?: return emptyList()
        val definingEquivalence = position.parent.elementType in equivalenceDefinitionParameterContexts
        return prefix.openMacro?.parametersByName?.keys.orEmpty().filter { definingEquivalence || prefix.equivalence(it) == null }
    }

    private fun macroBodyPrefix(root: MipsFile, position: PsiElement): MipsSourceTimeline.Prefix? {
        if (DumbService.isDumb(project)) return null
        // A parameter on an aliased opening line is a formal, before that line opens the scope.
        return editorPrefix(root, sourceLineStart(position))
    }

    internal fun generatedMacroPart(root: MipsFile, position: PsiElement): MacroPart? {
        if (DumbService.isDumb(project)) return null
        return editorTimeline(root).generatedMacroPart(root, position.textOffset)
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

    internal fun editorTimeline(file: MipsFile): MipsSourceTimeline {
        selectedTimeline(file)?.takeIf { it.includes(file) }?.let { return it }
        val source = file.viewProvider.virtualFile
        if (source.isInLocalFileSystem && !DumbService.isDumb(project)) {
            val root = inferredMipsAssemblyRoot(file)
            if (root != null && root != source) {
                val entry = PsiManager.getInstance(project).findFile(root) as MipsFile
                return mipsSourceTimeline(entry)
            }
        }
        return mipsSourceTimeline(file)
    }

    private fun selectedTimeline(file: MipsFile): MipsSourceTimeline? {
        val projectSources = selectedAssembly(file)?.sourceSet?.takeIf { it.mode == MarsAssemblyMode.PROJECT }
        val path: Path
        val root = if (projectSources != null) {
            path = file.viewProvider.virtualFile.toNioPath()
            if (path in projectSources.entries) path
            else collectBackgroundAssemblySources(project, projectSources).rootsBySource[path] ?: return null
        } else {
            val build = lastBuild.get()?.snapshot?.takeIf { it.isCurrent(exceptionHandler) } ?: return null
            path = build.sourcePath(file) ?: return null
            build.rootFor(path)
        }
        if (path == root) return mipsSourceTimeline(file)
        val entry = VirtualFileManager.getInstance().findFileByNioPath(root) ?: return null
        val entryPsi = PsiManager.getInstance(project).findFile(entry) as? MipsFile ?: return null
        return mipsSourceTimeline(entryPsi)
    }

    private fun AssemblySymbols.isCurrent(handler: Path?): Boolean =
        exceptionHandler == handler && (sourceSet.mode != MarsAssemblyMode.PROJECT || sourceSet == settings.projectSourceSet())

    /** Finds the definition used by the assembler to substitute this token. */
    internal fun equivalenceTarget(root: MipsFile, position: PsiElement): PsiElement? =
        equivalenceUse(root, position)?.target

    internal fun equivalenceUse(root: MipsFile, position: PsiElement): EquivalenceUse? {
        if (DumbService.isDumb(project)) return null
        return editorTimeline(root).equivalenceUse(root, position)
    }

    private fun MipsDeclarationSite.isLiteralLabel(file: MipsFile, timeline: MipsSourceTimeline): Boolean =
        kind == SymbolKind.LABEL && !timeline.replacesLabel(file, offset)

    private fun visibleLabel(name: String, owner: MipsSourceEvent.Begin?, active: MipsSourceEvent.Begin?): Boolean =
        owner == null || owner === active && !Macro.tokenIsMacroParameter(name, true)

    private fun declarations(file: MipsFile) =
        FileBasedIndex.getInstance().getFileData(MipsDeclarationIndex.NAME, file.viewProvider.virtualFile, project)

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
