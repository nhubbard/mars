package edu.missouristate.mars.plugin.analysis

import com.intellij.notebook.editor.BackedVirtualFile
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.containers.CollectionFactory
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.analysis.MacroBoundaryProblem.Kind
import edu.missouristate.mars.plugin.analysis.MipsSourceEvent.Begin
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.collectRegisterUsages
import edu.missouristate.mars.plugin.language.mipsAssemblySourceOrigin
import edu.missouristate.mars.plugin.language.mipsAssemblySourceView
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import edu.missouristate.mars.plugin.language.references.findMipsIncludeFile
import edu.missouristate.mars.plugin.language.references.mipsIncludeRequest
import edu.missouristate.mars.plugin.language.references.mipsIncludeDirectoryPath
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/** Include-ordered editor facts, cached against the PSI files that contributed them. */
internal fun mipsSourceTimeline(entry: MipsFile): MipsSourceTimeline = CachedValuesManager.getCachedValue(entry) {
    val dependencies = arrayListOf<Any>(
        VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, ProjectRootModificationTracker.getInstance(entry.project), entry,
    )
    var eventCount = 0
    val files = LinkedHashMap<VirtualFile, SourceFileEvents>()
    val visited = hashSetOf(entry.assemblySourceOriginalFile().includeIdentity())
    val equivalences = HashMap<String, IndexedEquivalence>()
    val externalStorage = LinkedHashMap<String, PsiElement>()
    val unindexedLabels = LinkedHashMap<String, MutableList<TimelineLabel>>()
    val macroBoundaries = TreeMap<Int, Begin?>()
    val macroNames = HashSet<String>()
    val closedMacros = LinkedHashMap<String, MutableMap<Int, TreeMap<Int, Begin>>>()
    val dataLists = TreeMap<Int, Directives?>()
    val pending = ArrayDeque<SourceCursor>()
    fun enter(file: MipsFile) {
        val locations = SourceFileEvents(file, files.size)
        files[file.viewProvider.virtualFile] = locations
        pending.addLast(SourceCursor(locations, mipsSourceEvents(file).iterator()))
    }

    enter(entry)
    var macro: Begin? = null
    var firstIncompleteInclude = Int.MAX_VALUE
    var unindexedSources = entry.isUnindexedAssemblySource()
    var inData = false
    var dataList = Directives.WORD
    var previousDataList: Directives? = null
    val replacement: (String) -> String? = { equivalences[it]?.definition?.replacement }
    fun addLabel(name: String, site: PsiElement, generated: Boolean) {
        if (macro != null && Macro.tokenIsMacroParameter(name, true)) return
        unindexedLabels.getOrPut(name, ::ArrayList).add(TimelineLabel(name, site, macro, generated))
    }
    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val cursor = pending.last()
        if (!cursor.events.hasNext()) {
            cursor.locations.end = eventCount
            pending.removeLast()
            continue
        }
        val physical = cursor.events.next()
        if (physical.offset < cursor.ignoreBefore) {
            if (physical is MipsSourceEvent.ExternalDeclaration) cursor.locations.replacedExternals.add(physical.offset)
            continue
        }
        val event = when (physical) {
            is MipsSourceEvent.Header -> physical.bind(macro != null, replacement) ?: continue
            is MipsSourceEvent.ExternalDeclaration -> {
                val expanded = physical.bind(macro != null, replacement)
                if (expanded.replaced) cursor.locations.replacedExternals.add(physical.offset)
                expanded.reservation ?: continue
            }
            is MipsSourceEvent.OperationCandidate -> physical.bind(macro != null, replacement) { name, site ->
                addLabel(name, site, generated = true)
            } ?: continue
            is MipsSourceEvent.LabeledMacro -> physical.bind(macro != null, replacement) ?: continue
            is MipsSourceEvent.LabelDeclaration -> {
                val expanded = physical.bind(macro != null, replacement)
                if (expanded == null) {
                    if (cursor.locations.isUnindexedSource) {
                        physical.declaration.name?.let { addLabel(it, physical.declaration, generated = false) }
                    }
                    continue
                }
                cursor.locations.replacedLabels.add(physical.offset)
                expanded.name?.let { addLabel(it, physical.declaration, generated = true) }
                cursor.ignoreBefore = expanded.ignoreBefore
                expanded.directive ?: continue
            }
            is MipsSourceEvent.Effective -> physical
        }
        val index = eventCount++
        cursor.locations.sites.add(SourceEventSite(event.offset, index))
        when (event) {
            is Begin -> {
                if (macro != null) {
                    if (firstIncompleteInclude == Int.MAX_VALUE) {
                        cursor.locations.macroBoundaryProblems.add(MacroBoundaryProblem(event.directiveOffset, Kind.NESTED_BEGIN))
                    }
                    continue
                }
                macroNames.add(event.name)
                macro = event
                cursor.locations.localMacros[event.header] = event
                if (event.header !is MipsDeclaration) {
                    cursor.locations.generatedMacroNames.add(event.nameOffset)
                    val parts = cursor.locations.generatedMacroParts
                    if (event.nameOffset != event.directiveOffset) parts[event.nameOffset] = MacroPart.NAME
                    for (formal in event.parameters) {
                        if (formal.offset != event.directiveOffset) parts.putIfAbsent(formal.offset, MacroPart.PARAMETER)
                    }
                }
                macroBoundaries[eventCount] = macro
            }
            is MipsSourceEvent.End -> {
                val lineStart = sourceLineStart(cursor.locations.file.viewProvider.contents, event.offset)
                cursor.locations.macroClosingLines.add(lineStart)
                val definition = macro
                if (definition == null && firstIncompleteInclude == Int.MAX_VALUE) {
                    cursor.locations.macroBoundaryProblems.add(MacroBoundaryProblem(event.offset, Kind.UNMATCHED_END))
                }
                if (definition != null && definition.header.containingFile === cursor.locations.file) {
                    cursor.locations.localMacroBodies.add(MacroBody(definition.header, event.offset))
                }
                val arity = definition?.arity
                if (arity != null) {
                    closedMacros.getOrPut(definition.name, ::LinkedHashMap)
                        .getOrPut(arity, ::TreeMap)[eventCount] = definition
                }
                macro = null
                macroBoundaries[eventCount] = null
            }
            is MipsSourceEvent.RejectedMacro -> if (firstIncompleteInclude == Int.MAX_VALUE) {
                cursor.locations.macroBoundaryProblems.add(MacroBoundaryProblem(event.offset, event.kind, event.lastOffset))
            }
            is MipsSourceEvent.DataDirective -> if (macro == null) {
                when (event.directive) {
                    Directives.DATA, Directives.KDATA -> inData = true
                    Directives.TEXT, Directives.KTEXT -> inData = false
                    else -> dataList = event.directive
                }
            }
            is MipsSourceEvent.Equivalence -> equivalences.putIfAbsent(event.name, IndexedEquivalence(index, event))
            is MipsSourceEvent.External -> {
                if (event.site !is MipsDeclaration || event.site.name == null || event.offset in cursor.locations.replacedExternals) {
                    cursor.locations.expandedExternals[event.site] = event
                }
                if (macro == null && event.canUseAsLabel) externalStorage.putIfAbsent(event.name, event.site)
            }
            is MipsSourceEvent.UnterminatedInclude -> firstIncompleteInclude = minOf(firstIncompleteInclude, index)
            is MipsSourceEvent.Include -> {
                val included = resolveMipsInclude(event.literal, event.path) as? PsiFile
                val file = included?.virtualFile ?: cursor.locations.file.assemblySourceOriginalFile()
                    .mipsIncludeRequest(event.path)?.let(VirtualFileManager.getInstance()::findMipsIncludeFile)
                val syntax = if (file != null && visited.add(file.includeIdentity())) {
                    if (included == null) {
                        unresolvedAssemblySourceSyntax(entry.project, file, dependencies::add)
                    } else {
                        dependencies.add(included)
                        // Binary PSI does not track a text document, so use the VFS content stamp.
                        if (included.fileType.isBinary) dependencies.add(file)
                        assemblySourceSyntax(included)
                    }
                } else null
                if (syntax == null) {
                    firstIncompleteInclude = minOf(firstIncompleteInclude, index)
                    continue
                }
                if (syntax.isUnindexedAssemblySource()) unindexedSources = true
                enter(syntax)
            }
        }
        val activeDataList = if (macro == null && inData) dataList else null
        if (activeDataList != previousDataList) {
            dataLists[eventCount] = activeDataList
            previousDataList = activeDataList
        }
    }
    val unclosed = macro
    if (unclosed != null && firstIncompleteInclude == Int.MAX_VALUE) {
        val openingFile = unclosed.header.containingFile.viewProvider.virtualFile
        files.getValue(openingFile).macroBoundaryProblems.add(MacroBoundaryProblem(unclosed.directiveOffset, Kind.MISSING_END))
    }
    CachedValueProvider.Result.create(
        MipsSourceTimeline(files, equivalences, dataLists, externalStorage, unindexedLabels, macroBoundaries,
            macroNames, closedMacros, firstIncompleteInclude, unindexedSources),
        dependencies,
    )
}

internal class MipsSourceTimeline(
    private val files: Map<VirtualFile, SourceFileEvents>,
    private val equivalences: Map<String, IndexedEquivalence>,
    private val dataLists: TreeMap<Int, Directives?>,
    val externalStorage: Map<String, PsiElement>,
    val unindexedLabels: Map<String, List<TimelineLabel>>,
    private val macroBoundaries: TreeMap<Int, Begin?>,
    val macroNames: Set<String>,
    private val closedMacros: Map<String, Map<Int, TreeMap<Int, Begin>>>,
    private val firstIncompleteInclude: Int,
    val hasUnindexedSources: Boolean,
) {
    val entryFile: MipsFile get() = files.values.first().file
    val hasIncompleteIncludes: Boolean get() = firstIncompleteInclude != Int.MAX_VALUE
    val hasQuotedEquivalence: Boolean by lazy { equivalences.values.any { '"' in it.definition.replacement } }

    // The assembler tokenizes all physical lines before expanding any macro calls.
    fun macroExpansionReplacement(name: String): String? = equivalences[name]?.definition?.replacement

    private val equivalenceUsesByLine = ConcurrentHashMap<Pair<MipsFile, Int>, LineEquivalenceUses>()
    private val registerUsagesByFile = ConcurrentHashMap<MipsFile, Map<Int, List<TextRange>>>()

    fun registerUsages(file: MipsFile): Map<Int, List<TextRange>> =
        registerUsagesByFile.getOrPut(file) { collectRegisterUsages(file, this) }

    private val generatedLabels by lazy { unindexedLabels.values.asSequence().flatten().filter(TimelineLabel::generated).toList() }

    val generatedLabelSites: Set<PsiElement> by lazy {
        generatedLabels.mapTo(HashSet(), TimelineLabel::site)
    }

    private val generatedLabelsByFile by lazy {
        generatedLabels.groupBy { it.site.containingFile }
    }

    fun generatedLabelsIn(file: MipsFile): List<TimelineLabel> = generatedLabelsByFile[file].orEmpty()

    private val generatedEquivalencesByFile by lazy {
        equivalences.values.map(IndexedEquivalence::definition).filter { it.site !is MipsDeclaration }
            .groupBy { it.site.containingFile }.mapValues { (_, definitions) ->
                definitions.associateBy(MipsSourceEvent.Equivalence::site)
            }
    }

    fun generatedEquivalencesIn(file: MipsFile): Map<PsiElement, MipsSourceEvent.Equivalence> =
        generatedEquivalencesByFile[file].orEmpty()

    fun expandedExternalsIn(file: MipsFile): Map<PsiElement, MipsSourceEvent.External> =
        files.getValue(file.viewProvider.virtualFile).expandedExternals

    fun replacesExternal(file: MipsFile, offset: Int): Boolean =
        offset in files.getValue(file.viewProvider.virtualFile).replacedExternals

    fun includes(file: MipsFile): Boolean = file.viewProvider.virtualFile in files

    // Index callers need the physical MARS PSI files, not the contextual parse copies.
    fun sourceFiles(): Sequence<MipsFile> = files.values.asSequence().filterNot(SourceFileEvents::isUnindexedSource)
        .map(SourceFileEvents::file)

    val indexedSourceScope: GlobalSearchScope by lazy {
        GlobalSearchScope.filesScope(files.values.first().file.project, sourceFiles().map { it.viewProvider.virtualFile }.toList())
    }

    fun sourceFile(file: VirtualFile): MipsFile = files.getValue(file).file

    fun sourceFileOrder(file: VirtualFile): Int = files.getValue(file).order

    fun unindexedSourceFiles(): Sequence<MipsFile> = files.values.asSequence().filter(SourceFileEvents::isUnindexedSource)
        .map(SourceFileEvents::file)

    fun replacesLabel(file: MipsFile, offset: Int): Boolean = offset in files.getValue(file.viewProvider.virtualFile).replacedLabels

    fun macroAt(file: MipsFile, offset: Int): Begin? = openMacro(prefixEnd(file, offset))

    fun localMacroBodies(file: MipsFile): List<MacroBody> =
        files.getValue(file.viewProvider.virtualFile).localMacroBodies

    fun macroBoundaryProblems(file: MipsFile): List<MacroBoundaryProblem> =
        files[file.viewProvider.virtualFile]?.macroBoundaryProblems.orEmpty()

    fun rejectsMacroHeaderOnLine(file: MipsFile, lineStart: Int): Boolean =
        lineStart in files.getValue(file.viewProvider.virtualFile).rejectedMacroHeaderLines

    fun localMacros(file: MipsFile): Map<PsiElement, Begin> =
        files.getValue(file.viewProvider.virtualFile).localMacros

    fun hasClosed(macro: Begin): Boolean =
        closedMacros[macro.name].orEmpty()[macro.parameters.size].orEmpty().containsValue(macro)

    fun isGeneratedMacroName(file: MipsFile, offset: Int): Boolean =
        offset in files.getValue(file.viewProvider.virtualFile).generatedMacroNames

    fun generatedMacroPart(file: MipsFile, offset: Int): MacroPart? =
        files.getValue(file.viewProvider.virtualFile).generatedMacroParts[offset]

    fun closesMacroOnLine(file: MipsFile, lineStart: Int): Boolean =
        lineStart in files.getValue(file.viewProvider.virtualFile).macroClosingLines

    private fun openMacro(end: Int): Begin? = macroBoundaries.floorEntry(end)?.value

    fun prefix(file: MipsFile, offset: Int): Prefix = Prefix(prefixEnd(file, offset))

    fun equivalenceUse(file: MipsFile, position: PsiElement): EquivalenceUse? {
        val start = sourceLineStart(position)
        val definition = prefix(file, start).equivalence(position.text) ?: return null
        val offset = position.textOffset - start
        val uses = equivalenceUses(file, position, start)
        if (offset !in uses.types) return null
        return EquivalenceUse(definition.site, uses.types[offset], offset == uses.operationOffset)
    }

    fun isNumericRepetition(file: MipsFile, position: PsiElement): Boolean {
        val start = sourceLineStart(position)
        // Completion analyzes a PSI copy with its placeholder in the slot being edited.
        val uses = if (position.containingFile === file) equivalenceUses(file, position, start)
            else analyzeEquivalences(file, position, start)
        return uses.numericRepetition
    }

    private fun equivalenceUses(file: MipsFile, position: PsiElement, start: Int): LineEquivalenceUses =
        equivalenceUsesByLine.getOrPut(file to start) { analyzeEquivalences(file, position, start) }

    private fun analyzeEquivalences(file: MipsFile, position: PsiElement, start: Int): LineEquivalenceUses {
        val prefix = prefix(file, start)
        val errors = ErrorList()
        val expansion = Tokenizer.tokenizeMappedLine(
            sourceLine(position, fromLineStart = true), errors,
            { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled,
        )
        val tokens = expansion.tokens()
        tokens.discardTrailingComment()
        val operationIndex = if (tokens.beginsWithLabel()) 2 else 0
        val operation = if (operationIndex < tokens.size()) tokens[operationIndex] else null
        val operationOffset = if (operation != null && prefix.operationType(tokens, operationIndex) != null) {
            expansion.originalOffset(operation.startPos - 1)
        } else -1
        val tokensByOffset = if (errors.errorsOccurred()) emptyMap() else (0 until tokens.size()).map(tokens::get).groupBy {
            expansion.originalOffset(it.startPos - 1)
        }
        val types = expansion.substitutionOffsets().associateWith { offset ->
            tokensByOffset[offset]?.singleOrNull()?.let {
                if (prefix.isMacroParameter(it.value)) TokenTypes.MACRO_PARAMETER else it.type
            }
        }
        repeat(operationIndex) { tokens.remove(0) }
        val numericRepetition = tokens.hasCompleteEquivalenceExpansion() && tokens.hasNumericRepetitionShape()
        return LineEquivalenceUses(types, operationOffset, numericRepetition)
    }

    private fun prefixEnd(file: MipsFile, offset: Int): Int {
        val locations = files.getValue(file.viewProvider.virtualFile)
        val match = locations.sites.binarySearchBy(offset) { it.offset }
        val insertion = if (match >= 0) match else -match - 1
        return locations.sites.getOrNull(insertion)?.index ?: locations.end
    }

    inner class Prefix(private val end: Int) {
        val hasIncompleteIncludes: Boolean get() = end > firstIncompleteInclude
        val dataList: Directives? get() = dataLists.floorEntry(end)?.value
        val openMacro: Begin? get() = openMacro(end)

        fun equivalence(name: String): MipsSourceEvent.Equivalence? =
            equivalences[name]?.takeIf { it.index < end }?.definition

        fun equivalencesStartingWith(prefix: String): Sequence<MipsSourceEvent.Equivalence> =
            equivalences.asSequence().filter { (name, entry) -> name.startsWith(prefix) && entry.index < end }
                .map { it.value.definition }

        fun isMacroParameter(name: String): Boolean = openMacro != null && Macro.tokenIsMacroParameter(name, true)

        fun operationType(tokens: TokenList, operationIndex: Int): TokenTypes? {
            val operation = tokens[operationIndex]
            return when {
                operation.type == TokenTypes.DIRECTIVE -> TokenTypes.DIRECTIVE
                isMacroParameter(operation.value) -> TokenTypes.MACRO_PARAMETER
                matchingMacro(tokens, operationIndex) != null -> TokenTypes.IDENTIFIER
                operation.type == TokenTypes.OPERATOR -> TokenTypes.OPERATOR
                else -> null
            }
        }

        fun matchingMacro(tokens: TokenList, operationIndex: Int): Begin? =
            macroOverload(tokens[operationIndex].value, tokens.macroArgumentCount(operationIndex))

        // Each history is keyed by the position after .end_macro, when that overload becomes visible.
        fun macroOverloads(name: String): Map<Int, Begin> = buildMap {
            for ((arity, history) in closedMacros[name].orEmpty()) {
                val definition = history.floorEntry(end) ?: continue
                put(arity, definition.value)
            }
        }

        fun macroOverload(name: String, arity: Int): Begin? =
            closedMacros[name]?.get(arity)?.floorEntry(end)?.value

        fun macroOverloads(acceptsName: (String) -> Boolean): List<Begin> =
            closedMacros.keys.filter {
                ProgressManager.checkCanceled()
                acceptsName(it)
            }.flatMap { macroOverloads(it).values }
    }
}

internal data class SourceEventSite(val offset: Int, val index: Int)

internal fun TokenList.macroArgumentCount(operationIndex: Int = 0): Int =
    size() - operationIndex - if (Macro.hasCallParentheses(this, operationIndex)) 3 else 1

internal enum class MacroPart { NAME, PARAMETER }

internal data class MacroBody(val opening: PsiElement, val closingOffset: Int)

internal data class MacroBoundaryProblem(val offset: Int, val kind: Kind, val lastOffset: Int = offset) {
    enum class Kind { MISSING_NAME, INVALID_NAME, NESTED_BEGIN, TRAILING_TEXT, UNMATCHED_END, MISSING_END }
}

internal class SourceFileEvents(val file: MipsFile, val order: Int) {
    val isUnindexedSource = file.isUnindexedAssemblySource()
    val sites = ArrayList<SourceEventSite>()
    val generatedMacroParts = HashMap<Int, MacroPart>()
    val replacedLabels = HashSet<Int>()
    val replacedExternals = HashSet<Int>()
    val expandedExternals = LinkedHashMap<PsiElement, MipsSourceEvent.External>()
    val macroClosingLines = HashSet<Int>()
    val macroBoundaryProblems = ArrayList<MacroBoundaryProblem>()
    val rejectedMacroHeaderLines: Set<Int> by lazy {
        val source = file.viewProvider.contents
        macroBoundaryProblems.asSequence().filter { it.kind in rejectedMacroHeaderKinds }
            .mapTo(HashSet()) { sourceLineStart(source, it.offset) }
    }
    val localMacros = LinkedHashMap<PsiElement, Begin>()
    val generatedMacroNames = HashSet<Int>()
    val localMacroBodies = ArrayList<MacroBody>()
    var end = 0
}

private val rejectedMacroHeaderKinds = setOf(Kind.MISSING_NAME, Kind.INVALID_NAME, Kind.NESTED_BEGIN)

internal data class IndexedEquivalence(val index: Int, val definition: MipsSourceEvent.Equivalence)

/** A substituted token has no single type when it supplies several tokens or the expansion fails. */
internal data class EquivalenceUse(val target: PsiElement, val type: TokenTypes?, val suppliesOperation: Boolean)

private data class LineEquivalenceUses(
    val types: Map<Int, TokenTypes?>, val operationOffset: Int, val numericRepetition: Boolean,
)

internal data class TimelineLabel(val name: String, val site: PsiElement, val macro: Begin?, val generated: Boolean)

private class SourceCursor(val locations: SourceFileEvents, val events: Iterator<MipsSourceEvent>) {
    var ignoreBefore = -1
}

internal fun resolveMipsInclude(literal: MipsStringLiteral, path: String) = MipsFileReferenceSet(path, literal).resolve()

private fun VirtualFile.includeIdentity() = mipsIncludeDirectoryPath() to name

internal fun assemblySourceSyntax(source: PsiFile?): MipsFile? {
    if (source == null) return null
    val file = source.viewProvider.virtualFile
    // The document may only contain the editor preview. Build captures the full source separately.
    if (FileSizeLimit.isTooLargeForContentLoading(file.length, file.extension)) return null
    if (source is MipsFile) return source
    return CachedValuesManager.getCachedValue(source) {
        // CachedValue can evaluate competing providers. Create PSI only for the published value.
        val syntax = lazy {
            if (source.fileType.isBinary) {
                readOnlyAssemblySource(source.project, file)
            } else {
                val copy = PsiFileFactory.getInstance(source.project)
                    .createFileFromText(source.name, MipsFileType, source.viewProvider.contents.toString()) as MipsFile
                // FileReferenceSet resolves relative paths against the original physical file.
                copy.setOriginalFile(source)
                copy
            }
        }
        CachedValueProvider.Result.create(syntax, source, file)
    }.value
}

internal fun unresolvedAssemblySourceSyntax(
    project: Project, file: VirtualFile, onDependency: (Any) -> Unit = {},
): MipsFile? {
    if (file.isDirectory) return null
    onDependency(file)
    if (FileSizeLimit.isTooLargeForContentLoading(file.length, file.extension)) return null
    // An ignored file has no physical PSI to retain its weakly cached view provider.
    val holder = project.service<UnresolvedAssemblySyntaxCache>().holders.getOrPut(file, ::UserDataHolderBase)
    val source = CachedValuesManager.getManager(project).getCachedValue(holder) {
        val name = file.name
        val type = file.fileType
        val document = if (type.isBinary) null else FileDocumentManager.getInstance().getDocument(file)
        val classification = ModificationTracker {
            if (file.name == name && file.fileType == type) 0 else 1
        }
        val syntax = lazy { readOnlyAssemblySource(project, file, document?.text, holder) }
        CachedValueProvider.Result.create(UnresolvedSource(syntax, document, classification),
            listOfNotNull(file, document, classification, ProjectRootModificationTracker.getInstance(project)))
    }
    onDependency(source.classification)
    source.document?.let(onDependency)
    return source.syntax.value
}

@Service(Service.Level.PROJECT)
private class UnresolvedAssemblySyntaxCache {
    val holders = CollectionFactory.createConcurrentWeakValueMap<VirtualFile, UserDataHolderBase>()
}

private val unresolvedSyntaxHolder = Key.create<UserDataHolderBase>("mars.assembly.unresolved.source")

private data class UnresolvedSource(
    val syntax: Lazy<MipsFile?>, val document: Document?, val classification: ModificationTracker,
)

private fun readOnlyAssemblySource(
    project: Project, file: VirtualFile, documentText: String? = null, holder: UserDataHolderBase? = null,
): MipsFile? {
    val text = documentText ?: readDiskAssemblyText(file) ?: return null
    val syntax = PsiManager.getInstance(project).findFile(mipsAssemblySourceView(file, text)) as MipsFile
    // Keep the cached value alive while an editor or timeline retains this PSI.
    syntax.putUserData(unresolvedSyntaxHolder, holder)
    return syntax
}

internal fun PsiFile.assemblySourceOriginalFile(): VirtualFile {
    val file = originalFile.viewProvider.virtualFile
    return file.mipsAssemblySourceOrigin ?: BackedVirtualFile.getOriginFileIfBacked(file)
}

private fun MipsFile.isUnindexedAssemblySource(): Boolean =
    originalFile !is MipsFile || viewProvider.virtualFile.mipsAssemblySourceOrigin != null
