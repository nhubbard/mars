package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
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
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.Token
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsDeclarationSite
import edu.missouristate.mars.plugin.language.index.MipsReferenceIndex
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

internal data class IndexedSymbolSite(val source: SourceId, val offset: Int, val macroOffset: Int)

internal data class OperationSite(val name: String, val type: TokenTypes, val range: TextRange)

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
        val build = lastBuild.get() ?: return null
        return build.request.selection.takeIf { build.snapshot.sourcePath(file) != null }
    }

    internal data class AssemblySelection(val entry: Path, val options: MarsExecutionOptions)

    internal class SymbolBuild(val selection: AssemblySelection) {
        val entry: Path get() = selection.entry
    }

    private class BuildSelection(
        val request: SymbolBuild,
        val snapshot: AssemblySymbols = AssemblySymbols(request.entry, options = request.selection.options),
    )

    internal fun beginSymbolBuild(entry: Path, options: MarsExecutionOptions = MarsExecutionOptions()): SymbolBuild =
        SymbolBuild(AssemblySelection(entry.toAbsolutePath().normalize(), options)).also { lastBuild.set(BuildSelection(it)) }

    internal fun publishBuiltSymbols(build: SymbolBuild, sources: CapturedAssemblySources, symbols: AssemblySymbolMap) {
        val current = lastBuild.get()
        if (current.request === build) {
            val snapshot = AssemblySymbols(build.entry, sources.revisions, symbols, sources.snapshot.texts(), build.selection.options)
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
        val options = selected?.first?.options ?: MarsExecutionOptions()
        val candidate = candidates.firstOrNull { it.first.entry == entry && it.first.options == options } ?: return null
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

    internal fun macroTarget(root: MipsFile, position: PsiElement): PsiElement? {
        val definition = macroDefinition(root, position) ?: return null
        val header = definition.header
        return header as? MipsDeclaration ?: header.containingFile.findElementAt(definition.nameOffset)
    }

    internal fun macroDefinition(root: MipsFile, position: PsiElement): MipsSourceEvent.Begin? {
        if (DumbService.isDumb(project)) return null
        val prefix = editorPrefix(root, position.textOffset)
        val tokens = expandedOperation(prefix, sourceLine(position, fromLineStart = true))
            ?.takeUnless(TokenList::isEmpty) ?: return null
        return matchingMacro(prefix, tokens)
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
        val tokens = expandedOperation(prefix, writtenOperationPrefix(head, source), allowIncompleteOperand = true)
            ?.takeUnless(TokenList::isEmpty) ?: return null
        val definitions = prefix.macroOverloads(tokens[0].value)
        if (definitions.isEmpty()) return null
        val fullTokens = expandedOperation(prefix, sourceLine(head, fromLineStart = true), allowIncompleteOperand = true)
        return MacroInvocation(tokens, fullTokens, definitions.values.map { definition -> definition.parameters.map { it.value } })
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
        val actual = macroArgumentCount(tokens)
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
        val expansion = Tokenizer.tokenizeMappedLine(
            source, ErrorList(), { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled,
        )
        val tokens = expansion.tokens().operationTokens()
        if (tokens.isEmpty) return null
        val operation = tokens[0]
        val type = effectiveOperationType(operation, prefix) ?: return null
        val offset = start + expansion.originalOffset(operation.startPos - 1)
        return OperationSite(operation.value, type, root.findElementAt(offset)!!.textRange)
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
        val type = if (tokens.isEmpty) null else effectiveOperationType(tokens[0], prefix)
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

    private fun effectiveOperationType(operation: Token, prefix: MipsSourceTimeline.Prefix): TokenTypes? =
        when {
            operation.type == TokenTypes.DIRECTIVE -> TokenTypes.DIRECTIVE
            prefix.macroOverloads(operation.value).isNotEmpty() -> TokenTypes.IDENTIFIER
            operation.type == TokenTypes.OPERATOR -> TokenTypes.OPERATOR
            else -> null
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
        return tokens[0].type == TokenTypes.OPERATOR || tokens[0].type == TokenTypes.IDENTIFIER
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

    private fun matchingMacro(prefix: MipsSourceTimeline.Prefix, tokens: TokenList): MipsSourceEvent.Begin? {
        if (prefix.openMacro != null) return null
        return prefix.macroOverload(tokens[0].value, macroArgumentCount(tokens))
    }

    private fun macroArgumentCount(tokens: TokenList): Int = tokens.size() - if (Macro.hasCallParentheses(tokens)) 3 else 1

    /** A null list means no enclosing macro. An empty list means an undeclared parameter. */
    internal fun macroParameterTargets(root: MipsFile, position: PsiElement): List<PsiElement>? {
        val macro = macroBodyPrefix(root, position)?.openMacro ?: return null
        val name = position.text
        // The assembler uses the first exact match when a parameter name is repeated.
        val parameter = macro.parametersByName[name] ?: return emptyList()
        return listOfNotNull(macro.header.containingFile.findElementAt(parameter.offset))
    }

    internal fun macroParameterNames(root: MipsFile, position: PsiElement): List<String> {
        val prefix = macroBodyPrefix(root, position) ?: return emptyList()
        return prefix.openMacro?.parametersByName?.keys.orEmpty().filter { prefix.equivalence(it) == null }
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
        val build = lastBuild.get()?.snapshot ?: return null
        val path = build.sourcePath(file) ?: return null
        if (path == build.entry) return mipsSourceTimeline(file)
        val entry = VirtualFileManager.getInstance().findFileByNioPath(build.entry) ?: return null
        val entryPsi = PsiManager.getInstance(project).findFile(entry) as? MipsFile ?: return null
        return mipsSourceTimeline(entryPsi)
    }

    /** Finds the first effective .eqv before the reference in the selected entry. */
    internal fun equivalenceTarget(root: MipsFile, position: PsiElement): PsiElement? {
        if (DumbService.isDumb(project)) return null
        return editorPrefix(root, sourceLineStart(position)).equivalence(position.text)?.site
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
