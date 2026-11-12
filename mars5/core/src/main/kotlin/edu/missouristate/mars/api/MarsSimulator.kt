package edu.missouristate.mars.api

/** Asynchronous execution control for an assembled MIPS program. */
interface MarsSimulator {
    /** Start or resume until exit, an exception, or a step limit. */
    fun run()

    /** Execute one instruction and then pause. */
    fun step()

    /** Pause at the next instruction boundary if the program is running. */
    fun pause()

    /** Stop at the next instruction boundary until another program is assembled or a step is undone. */
    fun stop()

    /** Undo the most recent instruction if history is available. */
    fun backstep()

    fun addListener(listener: SimulatorListener)
    fun removeListener(listener: SimulatorListener)
    fun state(): SimulatorState
}
