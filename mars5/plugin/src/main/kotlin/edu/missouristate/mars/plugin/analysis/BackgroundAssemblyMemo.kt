package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.containers.SLRUMap
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.plugin.MarsSessionService
import java.nio.file.Path

internal data class BackgroundAssemblyOutcome(
    val diagnostics: Map<Path?, List<AssemblyError>>,
    val symbols: AssemblySymbols?,
)

/** Holds completed, detached outcomes. IntelliJ owns the external pass and its cancellation. */
internal class BackgroundAssemblyMemo {
    private data class Revision(val entry: Path, val documents: Map<Path, Long>, val structure: Long)

    private val outcomes = SLRUMap<Revision, BackgroundAssemblyOutcome>(4, 4)

    fun assemble(sources: CapturedAssemblySources, structureStamp: Long): BackgroundAssemblyOutcome {
        ProgressManager.checkCanceled()
        val entry = sources.snapshot.entries().single()
        val key = Revision(entry, sources.revisions, structureStamp)
        synchronized(outcomes) { outcomes.get(key) }?.let { return it }
        val component = MarsSessionService.createMarsComponent()
        val result = component.context().use { sources.assembleIn(component, ProgressManager::checkCanceled) }
        val symbols = result.program()?.let {
            AssemblySymbols(entry, sources.revisions, it.symbolMap(), sources.snapshot.texts())
        }
        val outcome = BackgroundAssemblyOutcome(result.errors().groupBy(AssemblyError::file), symbols)
        ProgressManager.checkCanceled()
        synchronized(outcomes) { outcomes.put(key, outcome) }
        return outcome
    }

    fun clear() = synchronized(outcomes) { outcomes.clear() }
}
