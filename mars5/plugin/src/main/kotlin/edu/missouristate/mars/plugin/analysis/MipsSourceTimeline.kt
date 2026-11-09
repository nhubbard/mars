package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet
import java.util.BitSet

/** Include-ordered editor facts, cached against the PSI files that contributed them. */
internal fun mipsSourceTimeline(entry: MipsFile): MipsSourceTimeline = CachedValuesManager.getCachedValue(entry) {
    val dependencies = arrayListOf<Any>(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, entry)
    val events = ArrayList<MipsSourceEvent.Effective>()
    val files = HashMap<VirtualFile, SourceFileEvents>()
    val equivalences = HashMap<String, IndexedEquivalence>()
    val externalStorage = LinkedHashMap<String, PsiElement>()
    val integerDataPrefixes = BitSet()
    val pending = ArrayDeque<SourceCursor>()
    fun enter(file: MipsFile) {
        val locations = SourceFileEvents()
        files[file.viewProvider.virtualFile] = locations
        pending.addLast(SourceCursor(locations, mipsSourceEvents(file).iterator()))
    }

    enter(entry)
    var inMacro = false
    var inData = false
    var dataList = Directives.WORD
    val replacement: (String) -> String? = { equivalences[it]?.definition?.replacement }
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
            is MipsSourceEvent.OperationCandidate -> physical.bind(inMacro, replacement) ?: continue
            is MipsSourceEvent.Effective -> physical
        }
        val index = events.size
        cursor.locations.sites.add(SourceEventSite(event.offset, index))
        events.add(event)
        when (event) {
            is MipsSourceEvent.Begin -> inMacro = true
            is MipsSourceEvent.End -> inMacro = false
            is MipsSourceEvent.DataDirective -> if (!inMacro) {
                when (event.directive) {
                    Directives.DATA, Directives.KDATA -> inData = true
                    Directives.TEXT, Directives.KTEXT -> inData = false
                    else -> dataList = event.directive
                }
            }
            is MipsSourceEvent.Equivalence -> equivalences.putIfAbsent(event.name, IndexedEquivalence(index, event))
            is MipsSourceEvent.External -> if (!inMacro) externalStorage.putIfAbsent(event.name, event.site)
            is MipsSourceEvent.Include -> {
                val included = resolveMipsInclude(event.literal, event.path)
                if (included is MipsFile && included.viewProvider.virtualFile !in files) {
                    dependencies.add(included)
                    enter(included)
                }
            }
        }
        if (!inMacro && inData && Directives.isIntegerDirective(dataList)) integerDataPrefixes.set(events.size)
    }
    CachedValueProvider.Result.create(
        MipsSourceTimeline(events, files, equivalences, integerDataPrefixes, externalStorage), dependencies,
    )
}

internal class MipsSourceTimeline(
    private val events: List<MipsSourceEvent.Effective>,
    private val files: Map<VirtualFile, SourceFileEvents>,
    private val equivalences: Map<String, IndexedEquivalence>,
    private val integerDataPrefixes: BitSet,
    val externalStorage: Map<String, PsiElement>,
) {
    fun includes(file: MipsFile): Boolean = file.viewProvider.virtualFile in files

    fun prefix(file: MipsFile, offset: Int): Prefix {
        val locations = files.getValue(file.viewProvider.virtualFile)
        val match = locations.sites.binarySearchBy(offset) { it.offset }
        val insertion = if (match >= 0) match else -match - 1
        val end = locations.sites.getOrNull(insertion)?.index ?: locations.end
        return Prefix(events.subList(0, end), equivalences, end, integerDataPrefixes[end])
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

internal class SourceFileEvents {
    val sites = ArrayList<SourceEventSite>()
    var end = 0
}

internal data class IndexedEquivalence(val index: Int, val definition: MipsSourceEvent.Equivalence)

private data class SourceCursor(val locations: SourceFileEvents, val events: Iterator<MipsSourceEvent>)

internal fun resolveMipsInclude(literal: MipsStringLiteral, path: String) = MipsFileReferenceSet(path, literal).resolve()
