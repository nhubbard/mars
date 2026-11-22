package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.api.StepEvent

/** Bounded evidence of completed service-5 inputs, read on the simulator worker. */
internal class MarsIntegerInputObservation(private val expected: List<Int>) {
    private val observed = mutableListOf<Long>()
    private var overflow = false
    private var previousV0: Long? = null
    @Volatile var instructions: Long = 0
        private set

    @Synchronized
    fun instruction(event: StepEvent, v0: Long) {
        instructions++
        // The optional SYSCALL code does not affect its function. Exception entry
        // is not a completed input, and the returned value is captured after execution.
        if (event.instruction ushr 26 == 0 && event.instruction and 63 == 12 &&
            event.transfer != StepEvent.ExceptionEntry.INSTANCE && previousV0 == 5L) {
            if (observed.size < expected.size) observed += v0 else overflow = true
        }
        previousV0 = v0
    }

    @Synchronized
    fun matches() = !overflow && observed == expected.map(Int::toLong)
}
