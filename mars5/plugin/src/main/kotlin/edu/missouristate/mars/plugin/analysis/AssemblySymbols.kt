package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.run.MarsExecutionSources
import java.nio.file.Path
import java.util.TreeSet
import kotlin.io.path.invariantSeparatorsPathString

/** Immutable, detached facts shared by explicit and background assembly. */
internal class AssemblySymbols(
    private val capture: CapturedAssemblySources,
    val symbols: AssemblySymbolMap,
    val options: MarsExecutionOptions,
) {
    val entry get() = sourceSet.entry
    val sourceSet get() = capture.sourceSet
    val revisions get() = capture.revisions
    val texts get() = capture.snapshot.texts()
    val exceptionHandler get() = capture.exceptionHandler
    val sourceExcerpts by lazy { MarsExecutionSources(capture) }
    private val sourcesByUrl = revisions.keys.associateBy { VfsUtilCore.pathToUrl(it.invariantSeparatorsPathString) }
    val macroCallSites = symbols.macroCalls().entries.groupBy { it.key.location() }
    val assembledMacroUsages: Map<SourceToken, Set<SourceToken>> = buildMap<SourceToken, MutableSet<SourceToken>> {
        for ((site, calls) in symbols.macroCalls()) {
            ProgressManager.checkCanceled()
            for (call in calls) getOrPut(call.definition(), ::LinkedHashSet).add(site)
        }
    }
    val assembledLabelUsages: Map<SourceToken, Set<SourceToken>> by lazy {
        buildMap<SourceToken, MutableSet<SourceToken>> {
            for ((site, bindings) in symbols.symbolUses()) {
                ProgressManager.checkCanceled()
                for (binding in bindings) getOrPut(binding.definition(), ::LinkedHashSet).add(site)
            }
        }
    }
    val localSymbols get() = localSymbols(entry)

    fun rootFor(source: Path): Path = capture.rootsBySource.getValue(source)

    fun localSymbols(source: Path) = symbols.locals()[rootFor(source)].orEmpty()

    fun hasCurrentDirectory(): Boolean =
        capture.directoryStamp?.let { it == VirtualFileManager.getInstance().structureModificationCount } ?: true

    fun sourcePath(file: MipsFile): Path? = sourcesByUrl[file.viewProvider.virtualFile.url]

    private val localNames = symbols.locals().mapValues { collectNames(it.value) }
    private val globalNames = collectNames(symbols.globals())

    private fun collectNames(definitions: Map<String, AssemblySymbolMap.Definition>) = TreeSet<String>().apply {
        for ((name, definition) in definitions) {
            ProgressManager.checkCanceled()
            if (definition.macroPrivate()) continue
            val type = TokenTypes.matchTokenType(name)
            if (type == TokenTypes.IDENTIFIER || type == TokenTypes.OPERATOR) add(name)
        }
    }

    fun matchingNames(source: Path, prefix: String, localOnly: Boolean = false): List<String> = buildList {
        for (names in listOfNotNull(localNames[rootFor(source)], globalNames.takeUnless { localOnly })) {
            addAll(names.tailSet(prefix).asSequence().takeWhile { it.startsWith(prefix) })
        }
    }
}
