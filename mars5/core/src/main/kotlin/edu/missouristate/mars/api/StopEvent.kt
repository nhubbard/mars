package edu.missouristate.mars.api

import kotlin.jvm.JvmRecord

/** The reason and exit code reported when a simulation stops or pauses. */
@JvmRecord
data class StopEvent(val reason: Reason, val exitCode: Int) {
    enum class Reason {
        /** The program executed syscall 10 or 17. */
        NORMAL_EXIT,
        STOP_REQUEST,
        BREAKPOINT,
        PAUSE,
        BACKSTEP,
        /** An execution slice used its allotted steps. */
        STEP_LIMIT,
        /** The program used its launch-wide instruction budget. */
        INSTRUCTION_LIMIT,
        /** The program counter reached the end of the text segment without an explicit halt. */
        CLIFF_TERMINATION,
        EXCEPTION,
    }
}
