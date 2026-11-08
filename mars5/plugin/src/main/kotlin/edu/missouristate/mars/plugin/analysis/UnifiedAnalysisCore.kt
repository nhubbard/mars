package edu.missouristate.mars.plugin.analysis

/** A ticket belongs to one request on one core instance. Callers cannot forge a generation. */
class AnalysisTicket internal constructor(internal val generation: Long)

data class PublishedAnalysis(val generation: Long, val index: IndexSnapshot)

/** Owns coherent publications; old queries retain their immutable snapshot during replacement. */
class UnifiedAnalysisCore : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var latest: AnalysisTicket? = null
    private var closed = false
    @Volatile private var published = PublishedAnalysis(0, IndexSnapshot.build(emptyList()))

    val snapshot: PublishedAnalysis get() = published

    fun beginUpdate(): AnalysisTicket = synchronized(lock) {
        check(!closed) { "Analysis core is closed" }
        AnalysisTicket(++generation).also { latest = it }
    }

    fun publish(ticket: AnalysisTicket, index: IndexSnapshot): Boolean = synchronized(lock) {
        if (closed || ticket !== latest) return false
        published = PublishedAnalysis(ticket.generation, index)
        latest = null
        true
    }

    override fun close() = synchronized(lock) {
        closed = true
        latest = null
        // Release project data while allowing callers holding an old snapshot to finish safely.
        published = PublishedAnalysis(generation, IndexSnapshot.build(emptyList()))
    }
}
