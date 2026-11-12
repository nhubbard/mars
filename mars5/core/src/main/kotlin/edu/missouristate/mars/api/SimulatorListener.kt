package edu.missouristate.mars.api

/** Execution callbacks on the simulator thread. Implementations must return without blocking. */
interface SimulatorListener {
    /** Receive each completed instruction, including a terminating syscall. */
    fun onStep(event: StepEvent)

    /** Receive the number of completed instructions remaining after an undo. */
    fun onBackstep(stepCount: Long) = Unit

    fun onStop(event: StopEvent)

    fun onError(event: ErrorEvent)
}
