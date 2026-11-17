package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.MarsSimulator
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome
import edu.missouristate.mars.api.StopEvent

/** Completed forward events; branch decisions come from the core, without opcode reclassification. */
data class ExecutionCounts(val instructions: Long, val takenBranches: Long, val notTakenBranches: Long) {
    val branches: Long get() = takenBranches + notTakenBranches
}

/** An opt-in counter. Query or close it only while the assignment execution is stopped. */
class ExecutionTrace internal constructor(
    simulator: MarsSimulator,
    private val from: Long? = null,
    private val until: Long? = null,
) : AutoCloseable {
    private var subscription: MarsSimulator? = simulator
    private var instructions = 0L
    private var taken = 0L
    private var notTaken = 0L
    private val listener = object : SimulatorListener {
        override fun onStep(event: StepEvent) {
            if (from != null && (event.pcBits < from || event.pcBits >= until!!)) return
            instructions++
            when (event.branchOutcome) {
                BranchOutcome.TAKEN -> taken++
                BranchOutcome.NOT_TAKEN -> notTaken++
                null -> Unit
            }
        }

        override fun onError(event: ErrorEvent) = Unit
        override fun onStop(event: StopEvent) = Unit
    }

    init {
        require((from == null && until == null) || (from != null && until != null && from < until)) {
            "Choose a nonempty increasing instruction-address interval, or no interval"
        }
        simulator.addListener(listener)
    }

    fun snapshot() = ExecutionCounts(instructions, taken, notTaken)

    fun expectCounts(instructions: Long, takenBranches: Long, notTakenBranches: Long) {
        require(instructions >= 0 && takenBranches >= 0 && notTakenBranches >= 0) { "Expected counts must be nonnegative" }
        val expected = ExecutionCounts(instructions, takenBranches, notTakenBranches)
        val actual = snapshot()
        requireCase(actual == expected) { "Expected execution counts $expected, got $actual" }
    }

    override fun close() {
        subscription?.removeListener(listener)
        subscription = null
    }
}
