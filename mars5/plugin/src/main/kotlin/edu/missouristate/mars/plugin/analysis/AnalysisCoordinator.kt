package edu.missouristate.mars.plugin.analysis

import java.util.List.copyOf as copyOfList

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

/** Schedules cancellable joins in the owner's scope. It does not perform PSI or index reads. */
class AnalysisCoordinator(
    private val scope: CoroutineScope,
    private val build: (List<IndexContribution>, () -> Unit) -> IndexSnapshot = { facts, check ->
        IndexSnapshot.build(facts, check)
    },
) : AutoCloseable {
    private val lock = Any()
    private val core = UnifiedAnalysisCore()
    private var work: Deferred<Boolean>? = null

    val snapshot: PublishedAnalysis get() = core.snapshot

    /** Replaces the complete captured fact view. Per-file deltas must be merged by the acquisition layer. */
    fun submit(contributions: List<IndexContribution>): Deferred<Boolean> {
        val captured = copyOfList(contributions)
        return synchronized(lock) {
            val ticket = core.beginUpdate()
            work?.cancel()
            scope.async {
                val context = coroutineContext
                val index = build(captured) { context.ensureActive() }
                context.ensureActive()
                core.publish(ticket, index)
            }.also { work = it }
        }
    }

    override fun close() = synchronized(lock) {
        core.close()
        work?.cancel()
        work = null
    }
}
