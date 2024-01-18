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

package edu.missouristate.mars.mips.instructions.impl.math.singleprecision

import edu.missouristate.mars.bitsToFloat
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.toIntBits

class FloatSubtract : BasicInstruction(
    "sub.s \$f0,\$f1,\$f3",
    "Floating-point subtraction, single precision: set \$f0 to single-precision floating point value of \$f1 - \$f3",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 ttttt sssss fffff 000001",
    SimulationCode {
        val operands = it.getOperandsOrThrow()
        val sub1 = Coprocessor1.getValue(operands[1]).bitsToFloat()
        val sub2 = Coprocessor1.getValue(operands[2]).bitsToFloat()
        val diff = sub1 - sub2
        Coprocessor1.updateRegister(operands[0], diff.toIntBits())
    }
)