package edu.missouristate.mars.cli.grading

import java.util.Collections

/** Scripted service-30 results and service-32 observations; never reads or waits on host time. */
class TimingCheck internal constructor(readings: List<Long>, private val maxEvents: Int) {
    enum class Operation { TIME, SLEEP }

    data class Event(val operation: Operation, val milliseconds: Long)

    private val readings = readings.toList()
    private var nextReading = 0
    private val events = ArrayList<Event>()

    init {
        require(maxEvents > 0) { "Timing history capacity must be positive" }
    }

    /** Complete detached history in request order, including zero-length sleeps. */
    fun snapshot(): List<Event> = Collections.unmodifiableList(events.toList())

    internal fun currentTimeMillis(): Long {
        requireCase(nextReading < readings.size) { "Scripted timing readings exhausted" }
        val value = readings[nextReading]
        record(Operation.TIME, value)
        nextReading++
        return value
    }

    internal fun sleepMillis(milliseconds: Int) {
        requireCase(milliseconds >= 0) { "Negative sleep request: $milliseconds" }
        record(Operation.SLEEP, milliseconds.toLong())
    }

    private fun record(operation: Operation, milliseconds: Long) {
        requireCase(events.size < maxEvents) { "Timing history exceeds $maxEvents events" }
        events += Event(operation, milliseconds)
    }
}

internal class GradingTiming {
    private var timing: TimingCheck? = null

    val enabled: Boolean get() = timing != null

    fun configure(readings: List<Long>, maxEvents: Int): TimingCheck {
        requireCase(timing == null) { "Configure timing once per case" }
        return TimingCheck(readings, maxEvents).also { timing = it }
    }

    fun currentTimeMillis(): Long = configured().currentTimeMillis()

    fun sleepMillis(milliseconds: Int) = configured().sleepMillis(milliseconds)

    private fun configured(): TimingCheck {
        requireCase(timing != null) { "Configure timing before requesting time or sleep" }
        return checkNotNull(timing)
    }
}
