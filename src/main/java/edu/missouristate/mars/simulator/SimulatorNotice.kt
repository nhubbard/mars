package edu.missouristate.mars.simulator

import edu.missouristate.mars.venus.RunSpeedPanel

/**
 * Object provided to Observers of the Simulator.
 * They are notified at important phases of the runtime simulator,
 * such as start and stop of simulation.
 *
 * @author Pete Sanderson
 * @version January 2009
 */
data class SimulatorNotice(
    /**
     * Fetch the memory address that was accessed.
     */
    val action: Int,
    /**
     * Fetch the length in bytes of the access operation (4,2,1).
     */
    val maxSteps: Int,
    /**
     * Fetch the value of the access operation (the value read or written).
     */
    val runSpeed: Double,
    /**
     * Fetch the value of the access operation (the value read or written).
     */
    val programCounter: Int
) {
    /**
     * String representation indicates the access type, address and length in bytes
     */
    override fun toString(): String =
        "${if ((this.action == SIMULATOR_START)) "START " else "STOP  "}Max Steps ${this.maxSteps} Speed ${if ((this.runSpeed == RunSpeedPanel.UNLIMITED_SPEED)) "unlimited " else "$runSpeed inst/sec"}Prog Ctr ${this.programCounter}"

    companion object {
        const val SIMULATOR_START: Int = 0
        const val SIMULATOR_STOP: Int = 1
    }
}