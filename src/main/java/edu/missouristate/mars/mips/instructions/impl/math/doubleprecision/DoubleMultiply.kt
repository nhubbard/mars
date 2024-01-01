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

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.bitsToDouble
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.toLongBits
import edu.missouristate.mars.util.Binary

class DoubleMultiply : BasicInstruction(
    "mul.d, \$f2,\$f4,\$f6",
    "Floating-point multiplication, double-precision: set \$f2 to double-precision floating point value of \$f4 times \$f6",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 ttttt sssss fffff 000010",
    SimulationCode {
        val operands = it.getOperandsOrThrow()
        if (operands.any { o -> o % 2 == 1 }) throw ProcessingException(it, "All registers must be even-numbered!")
        val mul1 = Binary.twoIntegersToLong(
            Coprocessor1.getValue(operands[1] + 1),
            Coprocessor1.getValue(operands[1])
        ).bitsToDouble()
        val mul2 = Binary.twoIntegersToLong(
            Coprocessor1.getValue(operands[2] + 1),
            Coprocessor1.getValue(operands[2])
        ).bitsToDouble()
        val prod = (mul1 * mul2).toLongBits()
        Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(prod))
        Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(prod))
    }
)