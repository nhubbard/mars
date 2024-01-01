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

package edu.missouristate.mars.mips.instructions.impl.math.doubleprecision

import edu.missouristate.mars.bitsToDouble
import edu.missouristate.mars.inIntRange
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.util.Binary
import kotlin.math.round

class RoundDoubleToWord : BasicInstruction(
    "round.w.d \$f1,\$f2",
    "Round double-precision float to word: set \$f1 to 32-bit integer round of double-precision float in \$f2",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 00000 sssss fffff 001100",
    SimulationCode {
        val operands = InstructionSet.getEvenOperand(it, 1, "Second register must be even-numbered!")
        val doubleValue = Binary.twoIntegersToLong(
            Coprocessor1.getValue(operands[1] + 1),
            Coprocessor1.getValue(operands[1])
        ).bitsToDouble()
        val below: Int
        val above: Int
        var round = round(doubleValue).toInt()
        if (doubleValue.isNaN() || doubleValue.isInfinite() || !doubleValue.inIntRange()) {
            round = Int.MAX_VALUE
        } else {
            // If we are EXACTLY in the middle, then round to even!  To determine this,
            // find next higher integer and next lower integer, then see if distances
            // are exactly equal.
            if (doubleValue < 0.0) {
                above = doubleValue.toInt()
                below = above - 1
            } else {
                below = doubleValue.toInt()
                above = below + 1
            }
            // Are we exactly in the middle?
            if (doubleValue - below == above - doubleValue) {
                round = if (above % 2 == 0) above else below
            }
        }
        Coprocessor1.updateRegister(operands[0], round)
    }
)