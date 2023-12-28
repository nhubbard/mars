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

package edu.missouristate.mars.mips.instructions.impl.math

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.simulator.Exceptions

class Subtract : BasicInstruction(
    "sub \$t1,\$t2,\$t3",
    "Subtraction with overflow: set \$t1 to (\$t2 minus \$t3)",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 100010",
    SimulationCode {
        val operands = it.getOperandsOrThrow()
        val sub1 = RegisterFile.getValue(operands[0])
        val sub2 = RegisterFile.getValue(operands[1])
        val diff = sub1 - sub2
        // overflow on A-B detected when A and B have opposite signs and A-B has B's sign
        if ((sub1 >= 0 && sub2 < 0 && diff < 0) || (sub1 < 0 && sub2 >= 0 && diff >= 0))
            throw ProcessingException(it, "Arithmetic overflow!", Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION)
        RegisterFile.updateRegister(operands[0], diff)
    }
)