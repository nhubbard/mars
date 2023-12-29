/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
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
import kotlin.math.sqrt

class FloatSquareRoot : BasicInstruction(
    "sqrt.s \$f0,\$f1",
    "Floating-point square root, single precision: set \$f0 to single-precision floating point square root of \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 000100",
    SimulationCode {
        val operands = it.getOperandsOrThrow()
        val value = Coprocessor1.getValue(operands[1]).bitsToFloat()
        // This is subject to refinement later.  Release 4.0 defines the floor, ceil, trunc, and round
        // to act silently rather than raise Invalid Operation exception, so sqrt should do the
        // same.  An intermediate step would be to define a setting for FCSR Invalid Operation
        // flag, but the best solution is to simulate the FCSR register itself.
        // FCSR = Floating point unit Control and Status Register.
        val floatSqrt = (if (value < 0.0f) Float.NaN else sqrt(value)).toIntBits()
        Coprocessor1.updateRegister(operands[0], floatSqrt)
    }
)