package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Macro
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import java.util.BitSet
import java.util.TreeMap

/** Include-ordered editor facts, cached against the PSI files that contributed them. */
internal fun mipsSourceTimeline(entry: MipsFile): MipsSourceTimeline = CachedValuesManager.getCachedValue(entry) {
    val dependencies = arrayListOf<Any>(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, entry)
    val events = ArrayList<MipsSourceEvent.Effective>()
    val files = LinkedHashMap<VirtualFile, SourceFileEvents>()
    val equivalences = HashMap<String, IndexedEquivalence>()
    val externalStorage = LinkedHashMap<String, PsiElement>()
    val generatedLabels = LinkedHashMap<String, MutableList<GeneratedLabel>>()
    val macroBoundaries = TreeMap<Int, MipsSourceEvent.Begin?>()
    val integerDataPrefixes = BitSet()
    val pending = ArrayDeque<SourceCursor>()
    fun enter(file: MipsFile) {
        val locations = SourceFileEvents(file)
        files[file.viewProvider.virtualFile] = locations
        pending.addLast(SourceCursor(locations, mipsSourceEvents(file).iterator()))
    }

    enter(entry)
    var macro: MipsSourceEvent.Begin? = null
    var inData = false
    var dataList = Directives.WORD
    val replacement: (String) -> String? = { equivalences[it]?.definition?.replacement }
    fun addLabel(name: String, site: PsiElement) {
        if (macro != null && Macro.tokenIsMacroParameter(name, true)) return
        generatedLabels.getOrPut(name, ::ArrayList).add(GeneratedLabel(site, macro))
    }
    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val cursor = pending.last()
        if (!cursor.events.hasNext()) {
            cursor.locations.end = events.size
            pending.removeLast()
            continue
        }
        val event = when (val physical = cursor.events.next()) {
            is MipsSourceEvent.Header -> physical.bind(replacement) ?: continue
            is MipsSourceEvent.ExternalDeclaration -> physical.bind(replacement) ?: continue
            is MipsSourceEvent.OperationCandidate -> physical.bind(macro != null, replacement, ::addLabel) ?: continue
            is MipsSourceEvent.LabelDeclaration -> {
                val name = physical.expandedName(replacement) ?: continue
                cursor.locations.replacedLabels.add(physical.offset)
                addLabel(name, physical.declaration)
                continue
            }
            is MipsSourceEvent.Effective -> physical
        }
        val index = events.size
        cursor.locations.sites.add(SourceEventSite(event.offset, index))
        events.add(event)
        when (event) {
            is MipsSourceEvent.Begin -> {
                macro = macro ?: event
                macroBoundaries[events.size] = macro
            }
            is MipsSourceEvent.End -> {
                macro = null
                macroBoundaries[events.size] = null
            }
            is MipsSourceEvent.DataDirective -> if (macro == null) {
                when (event.directive) {
                    Directives.DATA, Directives.KDATA -> inData = true
                    Directives.TEXT, Directives.KTEXT -> inData = false
                    else -> dataList = event.directive
                }
            }
            is MipsSourceEvent.Equivalence -> equivalences.putIfAbsent(event.name, IndexedEquivalence(index, event))
            is MipsSourceEvent.External -> if (macro == null) externalStorage.putIfAbsent(event.name, event.site)
            is MipsSourceEvent.Include -> {
                val included = resolveMipsInclude(event.literal, event.path)
                if (included is MipsFile && included.viewProvider.virtualFile !in files) {
                    dependencies.add(included)
                    enter(included)
                }
            }
        }
        if (macro == null && inData && Directives.isIntegerDirective(dataList)) integerDataPrefixes.set(events.size)
    }
    CachedValueProvider.Result.create(
        MipsSourceTimeline(events, files, equivalences, integerDataPrefixes, externalStorage, generatedLabels, macroBoundaries),
        dependencies,
    )
}

internal class MipsSourceTimeline(
    private val events: List<MipsSourceEvent.Effective>,
    private val files: Map<VirtualFile, SourceFileEvents>,
    private val equivalences: Map<String, IndexedEquivalence>,
    private val integerDataPrefixes: BitSet,
    val externalStorage: Map<String, PsiElement>,
    val generatedLabels: Map<String, List<GeneratedLabel>>,
    private val macroBoundaries: TreeMap<Int, MipsSourceEvent.Begin?>,
) {
    fun includes(file: MipsFile): Boolean = file.viewProvider.virtualFile in files

    fun sourceFiles(): Sequence<MipsFile> = files.values.asSequence().map(SourceFileEvents::file)

    fun replacesLabel(file: MipsFile, offset: Int): Boolean = offset in files.getValue(file.viewProvider.virtualFile).replacedLabels

    fun macroAt(file: MipsFile, offset: Int): MipsSourceEvent.Begin? = macroBoundaries.floorEntry(prefixEnd(file, offset))?.value

    fun prefix(file: MipsFile, offset: Int): Prefix {
        val end = prefixEnd(file, offset)
        return Prefix(events.subList(0, end), equivalences, end, integerDataPrefixes[end])
    }

    private fun prefixEnd(file: MipsFile, offset: Int): Int {
        val locations = files.getValue(file.viewProvider.virtualFile)
        val match = locations.sites.binarySearchBy(offset) { it.offset }
        val insertion = if (match >= 0) match else -match - 1
        return locations.sites.getOrNull(insertion)?.index ?: locations.end
    }

    class Prefix(
        val events: List<MipsSourceEvent.Effective>,
        private val equivalences: Map<String, IndexedEquivalence>,
        private val end: Int,
        val hasIntegerDataList: Boolean,
    ) {
        fun equivalence(name: String): MipsSourceEvent.Equivalence? =
            equivalences[name]?.takeIf { it.index < end }?.definition
    }
}

internal data class SourceEventSite(val offset: Int, val index: Int)

internal class SourceFileEvents(val file: MipsFile) {
    val sites = ArrayList<SourceEventSite>()
    val replacedLabels = HashSet<Int>()
    var end = 0
}

internal data class IndexedEquivalence(val index: Int, val definition: MipsSourceEvent.Equivalence)

internal data class GeneratedLabel(val site: PsiElement, val macro: MipsSourceEvent.Begin?)

private data class SourceCursor(val locations: SourceFileEvents, val events: Iterator<MipsSourceEvent>)

internal fun resolveMipsInclude(literal: MipsStringLiteral, path: String) = MipsFileReferenceSet(path, literal).resolve()
