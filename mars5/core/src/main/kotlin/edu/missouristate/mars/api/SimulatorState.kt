package edu.missouristate.mars.api

/** The current execution state of a [MarsSimulator]. */
enum class SimulatorState {
    /** No program loaded or execution not started. */
    IDLE,

    /** Currently executing instructions. */
    RUNNING,

    /** Execution suspended at a breakpoint or after a step. */
    PAUSED,

    /** Execution stopped until a new program is loaded. */
    STOPPED,
}
