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

package edu.missouristate.mars.mips.instructions.impl.math.integer

import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.util.Binary

class MultiplyAddUnsigned : BasicInstruction(
    "maddu \$t1,\$t2",
    "Multiply add unsigned: multiply unsigned \$t1 by \$t2, then increment hi by high-order 32 bits of product and increment lo by low-order 32 bits of product (use mfhi to access high-order and mflo to access low-order)",
    BasicInstructionFormat.R_FORMAT,
    "011100 fffff sssss 00000 00000 000001",
    SimulationCode {
        val operands = it.getOperandsOrThrow()
        val product = ((RegisterFile.getValue(operands[0]).toLong()) shl 32 ushr 32) *
                ((RegisterFile.getValue(operands[1]).toLong()) shl 32 ushr 32)
        // Register 33 is hi and 34 is lo
        val contentsHiLo = Binary.twoIntegersToLong(RegisterFile.getValue(33), RegisterFile.getValue(34))
        val sum = contentsHiLo + product
        RegisterFile.updateRegister(33, Binary.highOrderLongToInt(sum))
        RegisterFile.updateRegister(34, Binary.lowOrderLongToInt(sum))
    }
)