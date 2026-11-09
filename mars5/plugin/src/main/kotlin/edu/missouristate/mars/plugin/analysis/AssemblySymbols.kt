package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.MarsExecutionOptions
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
) {
    private val sourcesByUrl = revisions.keys.associateBy { VfsUtilCore.pathToUrl(it.invariantSeparatorsPathString) }
    val localSymbols = symbols.locals()[entry].orEmpty()

    fun sourcePath(file: MipsFile): Path? = sourcesByUrl[file.viewProvider.virtualFile.url]

    private val completionNames = TreeSet<String>().apply {
        for (definitions in listOf(localSymbols, symbols.globals())) {
            for ((name, definition) in definitions) {
                ProgressManager.checkCanceled()
                if (definition.macroPrivate()) continue
                val type = TokenTypes.matchTokenType(name)
                if (type == TokenTypes.IDENTIFIER || type == TokenTypes.OPERATOR) add(name)
            }
        }
    }

    fun matchingNames(prefix: String): List<String> =
        completionNames.tailSet(prefix).asSequence().takeWhile { it.startsWith(prefix) }.toList()
}
