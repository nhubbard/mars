package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.MipsFile
import java.nio.file.Path
import java.util.TreeSet
import kotlin.io.path.invariantSeparatorsPathString

/** Immutable, detached facts shared by explicit and background assembly. */
internal class AssemblySymbols(
    val entry: Path,
    val revisions: Map<Path, Long> = emptyMap(),
    val symbols: AssemblySymbolMap = AssemblySymbolMap.EMPTY,
    val texts: Map<Path, String> = emptyMap(),
    val options: MarsExecutionOptions = MarsExecutionOptions(),
    private val rootsBySource: Map<Path, Path> = mapOf(entry to entry),
    val directoryStamp: Long? = null,
    val exceptionHandler: Path? = null,
    val sourceSet: MarsSourceSet = MarsSourceSet(entry),
) {
    private val sourcesByUrl = revisions.keys.associateBy { VfsUtilCore.pathToUrl(it.invariantSeparatorsPathString) }
    val localSymbols get() = localSymbols(entry)

    fun rootFor(source: Path): Path = rootsBySource.getValue(source)

    fun localSymbols(source: Path) = symbols.locals()[rootFor(source)].orEmpty()

    fun hasCurrentDirectory(): Boolean =
        directoryStamp?.let { it == VirtualFileManager.getInstance().structureModificationCount } ?: true

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

    fun matchingNames(source: Path, prefix: String): List<String> = buildList {
        for (names in listOfNotNull(localNames[rootFor(source)], globalNames)) {
            addAll(names.tailSet(prefix).asSequence().takeWhile { it.startsWith(prefix) })
        }
    }
}
