package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.containers.SLRUMap
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsSourceSet
import java.nio.file.Path

internal data class BackgroundAssemblyOutcome(
    val diagnostics: Map<Path?, List<AssemblyError>>,
    val symbols: AssemblySymbols?,
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
        val result = component.context().use { session ->
            options.applyTo(session.settings)
            sources.assembleIn(component, ProgressManager::checkCanceled)
        }
        val symbols = result.program()?.let {
            AssemblySymbols(sources.sourceSet.entry, sources.revisions, it.symbolMap(), sources.snapshot.texts(),
                options, sources.rootsBySource, sources.directoryStamp, sources.exceptionHandler, sources.sourceSet)
        }
        val outcome = BackgroundAssemblyOutcome(result.errors().groupBy(AssemblyError::file), symbols)
        ProgressManager.checkCanceled()
        synchronized(outcomes) { outcomes.put(key, outcome) }
        return outcome
    }

    fun clear() = synchronized(outcomes) { outcomes.clear() }
}
