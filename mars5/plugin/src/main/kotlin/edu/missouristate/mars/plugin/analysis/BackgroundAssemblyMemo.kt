package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.containers.SLRUMap
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.Program
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsSourceSet
import java.nio.file.Path

internal data class BackgroundAssemblyOutcome(
    val diagnostics: Map<Path?, List<AssemblyError>>,
    val symbols: AssemblySymbols?,
    val pseudoExpansions: Map<Path, Map<Int, List<String>>>,
)

/** Holds completed, detached outcomes. IntelliJ owns the external pass and its cancellation. */
internal class BackgroundAssemblyMemo {
    private data class Revision(
        val sourceSet: MarsSourceSet, val exceptionHandler: Path?,
        val documents: Map<Path, Long>, val structure: Long, val options: MarsExecutionOptions,
    )

    private val outcomes = SLRUMap<Revision, BackgroundAssemblyOutcome>(4, 4)

    fun assemble(sources: CapturedAssemblySources, structureStamp: Long, options: MarsExecutionOptions): BackgroundAssemblyOutcome {
        ProgressManager.checkCanceled()
        val key = Revision(sources.sourceSet, sources.exceptionHandler, sources.revisions, structureStamp, options)
        synchronized(outcomes) { outcomes.get(key) }?.let { return it }
        val component = MarsSessionService.createMarsComponent()
        val outcome = component.context().use { session ->
            options.applyForAssembly(session)
            val result = sources.assembleIn(component, ProgressManager::checkCanceled)
            val program = result.program()
            val symbols = program?.let {
                AssemblySymbols(sources.sourceSet.entry, sources.revisions, it.symbolMap(), sources.snapshot.texts(),
                    options, sources.rootsBySource, sources.directoryStamp, sources.exceptionHandler, sources.sourceSet)
            }
            BackgroundAssemblyOutcome(result.errors().groupBy(AssemblyError::file), symbols, program?.pseudoExpansions().orEmpty())
        }
        ProgressManager.checkCanceled()
        synchronized(outcomes) { outcomes.put(key, outcome) }
        return outcome
    }

    fun clear() = synchronized(outcomes) { outcomes.clear() }
}

private fun Program.pseudoExpansions(): Map<Path, Map<Int, List<String>>> = buildMap<Path, MutableMap<Int, MutableList<String>>> {
    for (instruction in pseudoInstructions(ProgressManager::checkCanceled)) {
        ProgressManager.checkCanceled()
        // Macro arguments are concrete at the outermost invocation, not in the macro definition.
        val location = generateSequence(instruction.origin(), Origin::caller).last().location()
        getOrPut(location.file(), ::LinkedHashMap).getOrPut(location.line(), ::ArrayList).add(instruction.assembly())
    }
}
