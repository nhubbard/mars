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

package edu.missouristate.mars.mips.instructions.impl.math.singleprecision

import edu.missouristate.mars.bitsToFloat
import edu.missouristate.mars.inIntRange
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import kotlin.math.round

class RoundFloatToWord : BasicInstruction(
    "round.w.s \$f0,\$f1",
    "Round single-precision float to word: set \$f0 to single-precision float in \$f1 rounded to nearest 32-bit integer",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 001100",
    SimulationCode {
        // MIPS32 documentation (and IEEE 754) states that round rounds to the nearest but when
        // both are equally near it rounds to the even one!  SPIM rounds -4.5, -5.5,
        // 4.5 and 5.5 to (-4, -5, 5, 6).  Curiously, it rounds -5.1 to -4 and -5.6 to -5.
        // Until MARS 3.5, I used Math.round, which rounds to the nearest, but when both are
        // equal it rounds toward positive infinity.  With Release 3.5, I painstakingly
        // carry out the MIPS and IEEE 754 standard.
        val operands = it.getOperandsOrThrow()
        val floatValue = Coprocessor1.getValue(operands[1]).bitsToFloat()
        val below: Int
        val above: Int
        var round = round(floatValue).toInt()
        // According to MIPS32 spec, if any of these conditions is true, set
        // Invalid Operation in the FCSR (Floating point Control/Status Register) and
        // sets the result to be 2^31-1.  MARS does not implement this register (as of release 3.4.1).
        // It also mentions the "Invalid Operation Enable bit" in FCSR, that, if set, results
        // in immediate exception instead of default value.
        if (floatValue.isNaN() || floatValue.isInfinite() || !floatValue.inIntRange()) {
            round = Int.MAX_VALUE
        } else {
            // If we are EXACTLY in the middle, then round to even!  To determine this,
            // find next higher integer and next lower integer, then see if distances
            // are exactly equal.
            if (floatValue < 0f) {
                // Truncating operation
                above = floatValue.toInt()
                below = above - 1
            } else {
                // Truncating operation
                below = floatValue.toInt()
                above = below + 1
            }
            // Exactly in the middle?
            if (floatValue - below == above - floatValue) {
                round = if (above % 2 == 0) above else below
            }
        }
        Coprocessor1.updateRegister(operands[0], round)
    }
)