/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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