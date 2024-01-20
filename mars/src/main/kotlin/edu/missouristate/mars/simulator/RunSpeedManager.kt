/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.simulator

import kotlin.concurrent.Volatile

object RunSpeedManager {
    /**
     * Constant that represents unlimited run speed.  Compare with the return value of
     * getRunSpeed() to determine if set to unlimited.  At the unlimited setting, the GUI
     * will not attempt to update register and memory contents as each instruction
     * is executed.  This is the only possible value for command-line use of Mars.
     */
    const val UNLIMITED_SPEED: Double = 40.0

    const val SPEED_INDEX_MIN = 0
    const val SPEED_INDEX_MAX = 40
    const val SPEED_INDEX_INIT = 40
    const val SPEED_INDEX_INTERACTION_LIMIT = 35

    @JvmField val speedTable = doubleArrayOf(
        .05, .1, .2, .3, .4, .5, 1.0, 2.0, 3.0, 4.0, 5.0,  // 0-10
        6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0,  // 11-20
        16.0, 17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0,  // 21-30
        26.0, 27.0, 28.0, 29.0, 30.0, UNLIMITED_SPEED, UNLIMITED_SPEED,  // 31-37
        UNLIMITED_SPEED, UNLIMITED_SPEED, UNLIMITED_SPEED // 38-40
    )

    @Volatile var runSpeedIndex = SPEED_INDEX_MAX

    val runSpeed: Double get() = speedTable[runSpeedIndex]
}