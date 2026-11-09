package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.psi.MipsStringLiteral
import edu.missouristate.mars.plugin.language.references.MipsFileReferenceSet

/** Include-ordered editor facts, cached against the PSI files that contributed them. */
internal fun mipsSourceTimeline(entry: MipsFile): MipsSourceTimeline = CachedValuesManager.getCachedValue(entry) {
    val dependencies = arrayListOf<Any>(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, entry)
    val events = ArrayList<MipsSourceEvent>()
    val files = HashMap<VirtualFile, SourceFileEvents>()
    val equivalences = HashMap<String, IndexedEquivalence>()
    val pending = ArrayDeque<SourceCursor>()
    fun enter(file: MipsFile) {
        val locations = SourceFileEvents()
        files[file.viewProvider.virtualFile] = locations
        pending.addLast(SourceCursor(locations, mipsSourceEvents(file).iterator()))
    }

    enter(entry)
    var inMacro = false
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
            is MipsSourceEvent.OperationCandidate -> physical.bind(inMacro, replacement) ?: continue
            else -> physical
        }
        val index = events.size
        cursor.locations.sites.add(SourceEventSite(event.offset, index))
        events.add(event)
        when (event) {
            is MipsSourceEvent.Begin -> inMacro = true
            is MipsSourceEvent.End -> inMacro = false
            is MipsSourceEvent.Equivalence -> equivalences.putIfAbsent(event.name, IndexedEquivalence(index, event))
            is MipsSourceEvent.Include -> {
                val included = resolveMipsInclude(event.literal, event.path)
                if (included is MipsFile && included.viewProvider.virtualFile !in files) {
                    dependencies.add(included)
                    enter(included)
                }
            }
            else -> Unit
        }
    }
    CachedValueProvider.Result.create(MipsSourceTimeline(events, files, equivalences), dependencies)
}

internal class MipsSourceTimeline(
    private val events: List<MipsSourceEvent>,
    private val files: Map<VirtualFile, SourceFileEvents>,
    private val equivalences: Map<String, IndexedEquivalence>,
) {
    fun entryPrefix(file: MipsFile, offset: Int): Prefix = prefix(files.getValue(file.viewProvider.virtualFile), offset)

    fun includedPrefix(file: MipsFile, offset: Int): Prefix? =
        files[file.viewProvider.virtualFile]?.let { prefix(it, offset) }

    private fun prefix(locations: SourceFileEvents, offset: Int): Prefix {
        val match = locations.sites.binarySearchBy(offset) { it.offset }
        val insertion = if (match >= 0) match else -match - 1
        val end = locations.sites.getOrNull(insertion)?.index ?: locations.end
        return Prefix(events.subList(0, end), equivalences, end)
    }

    class Prefix(
        val events: List<MipsSourceEvent>,
        private val equivalences: Map<String, IndexedEquivalence>,
        private val end: Int,
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
