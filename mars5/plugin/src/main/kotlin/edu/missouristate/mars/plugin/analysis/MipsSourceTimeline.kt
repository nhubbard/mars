package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.analysis.MipsSourceEvent.Begin
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import java.util.BitSet
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/** Include-ordered editor facts, cached against the PSI files that contributed them. */
internal fun mipsSourceTimeline(entry: MipsFile): MipsSourceTimeline = CachedValuesManager.getCachedValue(entry) {
    val dependencies = arrayListOf<Any>(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, entry)
    var eventCount = 0
    val files = LinkedHashMap<VirtualFile, SourceFileEvents>()
    val equivalences = HashMap<String, IndexedEquivalence>()
    val externalStorage = LinkedHashMap<String, PsiElement>()
    val generatedLabels = LinkedHashMap<String, MutableList<GeneratedLabel>>()
    val macroBoundaries = TreeMap<Int, Begin?>()
    val macroNames = HashSet<String>()
    val closedMacros = LinkedHashMap<String, MutableMap<Int, TreeMap<Int, Begin>>>()
    val integerDataPrefixes = BitSet()
    val pending = ArrayDeque<SourceCursor>()
    fun enter(file: MipsFile) {
        val locations = SourceFileEvents(file)
        files[file.viewProvider.virtualFile] = locations
        pending.addLast(SourceCursor(locations, mipsSourceEvents(file).iterator()))
    }

    enter(entry)
    var macro: Begin? = null
    var inData = false
    var dataList = Directives.WORD
    val replacement: (String) -> String? = { equivalences[it]?.definition?.replacement }
    fun addLabel(name: String, site: PsiElement) {
        if (macro != null && Macro.tokenIsMacroParameter(name, true)) return
        generatedLabels.getOrPut(name, ::ArrayList).add(GeneratedLabel(name, site, macro))
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
            is MipsSourceEvent.Header -> physical.bind(replacement) ?: continue
            is MipsSourceEvent.ExternalDeclaration -> {
                val expanded = physical.bind(replacement) ?: continue
                if (expanded.replaced) cursor.locations.replacedExternals.add(physical.offset)
                expanded.reservation ?: continue
            }
            is MipsSourceEvent.OperationCandidate -> physical.bind(macro != null, replacement, ::addLabel) ?: continue
            is MipsSourceEvent.LabelDeclaration -> {
                val expanded = physical.bind(macro != null, replacement) ?: continue
                cursor.locations.replacedLabels.add(physical.offset)
                expanded.name?.let { addLabel(it, physical.declaration) }
                cursor.ignoreBefore = expanded.ignoreBefore
                expanded.directive ?: continue
            }
            is MipsSourceEvent.Effective -> physical
        }
        val index = eventCount++
        cursor.locations.sites.add(SourceEventSite(event.offset, index))
        when (event) {
            is Begin -> {
                macroNames.add(event.name)
                if (macro == null) {
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
                }
                macroBoundaries[eventCount] = macro
            }
            is MipsSourceEvent.End -> {
                val lineStart = sourceLineStart(cursor.locations.file.viewProvider.contents, event.offset)
                cursor.locations.macroClosingLines.add(lineStart)
                val definition = macro
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
            is MipsSourceEvent.DataDirective -> if (macro == null) {
                when (event.directive) {
                    Directives.DATA, Directives.KDATA -> inData = true
                    Directives.TEXT, Directives.KTEXT -> inData = false
                    else -> dataList = event.directive
                }
            }
            is MipsSourceEvent.Equivalence -> equivalences.putIfAbsent(event.name, IndexedEquivalence(index, event))
            is MipsSourceEvent.External -> {
                if (event.site !is MipsDeclaration || event.offset in cursor.locations.replacedExternals) {
                    cursor.locations.expandedExternals[event.site] = event
                }
                if (macro == null) externalStorage.putIfAbsent(event.name, event.site)
            }
            is MipsSourceEvent.Include -> {
                val included = resolveMipsInclude(event.literal, event.path)
                if (included is MipsFile && included.viewProvider.virtualFile !in files) {
                    dependencies.add(included)
                    enter(included)
                }
            }
        }
        if (macro == null && inData && Directives.isIntegerDirective(dataList)) integerDataPrefixes.set(eventCount)
    }
    CachedValueProvider.Result.create(
        MipsSourceTimeline(files, equivalences, integerDataPrefixes, externalStorage, generatedLabels, macroBoundaries, macroNames, closedMacros),
        dependencies,
    )
}

internal class MipsSourceTimeline(
    private val files: Map<VirtualFile, SourceFileEvents>,
    private val equivalences: Map<String, IndexedEquivalence>,
    private val integerDataPrefixes: BitSet,
    val externalStorage: Map<String, PsiElement>,
    val generatedLabels: Map<String, List<GeneratedLabel>>,
    private val macroBoundaries: TreeMap<Int, Begin?>,
    val macroNames: Set<String>,
    private val closedMacros: Map<String, Map<Int, TreeMap<Int, Begin>>>,
) {
    private val equivalenceUsesByLine = ConcurrentHashMap<Pair<MipsFile, Int>, Map<Int, TokenTypes?>>()

    val generatedLabelSites: Set<PsiElement> by lazy {
        generatedLabels.values.flatten().mapTo(HashSet(), GeneratedLabel::site)
    }

    private val generatedLabelsByFile by lazy {
        generatedLabels.values.flatten().groupBy { it.site.containingFile }
    }

    fun generatedLabelsIn(file: MipsFile): List<GeneratedLabel> = generatedLabelsByFile[file].orEmpty()

    private val generatedEquivalencesByFile by lazy {
        equivalences.values.map(IndexedEquivalence::definition).filter { it.site !is MipsDeclaration }
            .groupBy { it.site.containingFile }
    }

    fun generatedEquivalencesIn(file: MipsFile): List<MipsSourceEvent.Equivalence> = generatedEquivalencesByFile[file].orEmpty()

    fun expandedExternalsIn(file: MipsFile): Map<PsiElement, MipsSourceEvent.External> =
        files.getValue(file.viewProvider.virtualFile).expandedExternals

    fun replacesExternal(file: MipsFile, offset: Int): Boolean =
        offset in files.getValue(file.viewProvider.virtualFile).replacedExternals

    fun includes(file: MipsFile): Boolean = file.viewProvider.virtualFile in files

    fun sourceFiles(): Sequence<MipsFile> = files.values.asSequence().map(SourceFileEvents::file)

    fun replacesLabel(file: MipsFile, offset: Int): Boolean = offset in files.getValue(file.viewProvider.virtualFile).replacedLabels

    fun macroAt(file: MipsFile, offset: Int): Begin? = openMacro(prefixEnd(file, offset))

    fun localMacroBodies(file: MipsFile): List<MacroBody> =
        files.getValue(file.viewProvider.virtualFile).localMacroBodies

    fun localMacros(file: MipsFile): Map<PsiElement, Begin> =
        files.getValue(file.viewProvider.virtualFile).localMacros

    fun isGeneratedMacroName(file: MipsFile, offset: Int): Boolean =
        offset in files.getValue(file.viewProvider.virtualFile).generatedMacroNames

    fun generatedMacroPart(file: MipsFile, offset: Int): MacroPart? =
        files.getValue(file.viewProvider.virtualFile).generatedMacroParts[offset]

    fun closesMacroOnLine(file: MipsFile, lineStart: Int): Boolean =
        lineStart in files.getValue(file.viewProvider.virtualFile).macroClosingLines

    private fun openMacro(end: Int): Begin? = macroBoundaries.floorEntry(end)?.value

    fun prefix(file: MipsFile, offset: Int): Prefix = Prefix(prefixEnd(file, offset))

    /** A physical alias has one color only when its expansion supplies one token. */
    fun equivalenceUseType(file: MipsFile, position: PsiElement): TokenTypes? {
        val start = sourceLineStart(position)
        return equivalenceUses(file, position, start)[position.textOffset - start]
    }

    fun equivalenceUseTarget(file: MipsFile, position: PsiElement): PsiElement? {
        val start = sourceLineStart(position)
        val definition = prefix(file, start).equivalence(position.text) ?: return null
        return definition.site.takeIf { position.textOffset - start in equivalenceUses(file, position, start) }
    }

    private fun equivalenceUses(file: MipsFile, position: PsiElement, start: Int): Map<Int, TokenTypes?> =
        equivalenceUsesByLine.getOrPut(file to start) {
            val prefix = prefix(file, start)
            val errors = ErrorList()
            val expansion = Tokenizer.tokenizeMappedLine(
                sourceLine(position, fromLineStart = true), errors,
                { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled,
            )
            val tokens = expansion.tokens()
            val types = if (errors.errorsOccurred()) emptyMap() else (0 until tokens.size()).map(tokens::get).groupBy {
                expansion.originalOffset(it.startPos - 1)
            }.mapValues { (_, tokens) -> tokens.singleOrNull()?.type }
            expansion.substitutionOffsets().associateWith(types::get)
        }

    private fun prefixEnd(file: MipsFile, offset: Int): Int {
        val locations = files.getValue(file.viewProvider.virtualFile)
        val match = locations.sites.binarySearchBy(offset) { it.offset }
        val insertion = if (match >= 0) match else -match - 1
        return locations.sites.getOrNull(insertion)?.index ?: locations.end
    }

    inner class Prefix(private val end: Int) {
        val hasIntegerDataList: Boolean get() = integerDataPrefixes[end]
        val openMacro: Begin? get() = openMacro(end)

        fun equivalence(name: String): MipsSourceEvent.Equivalence? =
            equivalences[name]?.takeIf { it.index < end }?.definition

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

internal enum class MacroPart { NAME, PARAMETER }

internal data class MacroBody(val opening: PsiElement, val closingOffset: Int)

internal class SourceFileEvents(val file: MipsFile) {
    val sites = ArrayList<SourceEventSite>()
    val generatedMacroParts = HashMap<Int, MacroPart>()
    val replacedLabels = HashSet<Int>()
    val replacedExternals = HashSet<Int>()
    val expandedExternals = LinkedHashMap<PsiElement, MipsSourceEvent.External>()
    val macroClosingLines = HashSet<Int>()
    val localMacros = LinkedHashMap<PsiElement, Begin>()
    val generatedMacroNames = HashSet<Int>()
    val localMacroBodies = ArrayList<MacroBody>()
    var end = 0
}

internal data class IndexedEquivalence(val index: Int, val definition: MipsSourceEvent.Equivalence)

internal data class GeneratedLabel(val name: String, val site: PsiElement, val macro: MipsSourceEvent.Begin?)

private class SourceCursor(val locations: SourceFileEvents, val events: Iterator<MipsSourceEvent>) {
    var ignoreBefore = -1
}

internal fun resolveMipsInclude(literal: MipsStringLiteral, path: String) = MipsFileReferenceSet(path, literal).resolve()
