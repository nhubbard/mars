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

package edu.missouristate.mars.mips.instructions.impl.memory

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD

// No printed reference; opcode is from SPIM
class LoadDwordIntoFPU : BasicInstruction(
    "ldc1 \$f2,-100(\$t2)",
    "Load double word into FPU: set \$f2 to 64-bit value from effective memory double-word address",
    BasicInstructionFormat.I_FORMAT,
    "110101 ttttt fffff ssssssssssssssss",
    SimulationCode {
        val operands = InstructionSet.getEvenOperand(it, 0, "First register must be even-numbered!")
        if (!Memory.doubleWordAligned(RegisterFile.getValue(operands[2]) + operands[1]))
            throw ProcessingException(
                it, AddressErrorException(
                    "Address not aligned on double-word boundary! ",
                    RegisterFile.getValue(operands[2]) + operands[1],
                    ADDRESS_EXCEPTION_LOAD
                )
            )
        try {
            Coprocessor1.updateRegister(
                operands[0],
                Globals.memory.getWord(RegisterFile.getValue(operands[2]) + operands[1])
            )
            Coprocessor1.updateRegister(
                operands[0] + 1,
                Globals.memory.getWord(RegisterFile.getValue(operands[2]) + operands[1] + 4)
            )
        } catch (e: AddressErrorException) {
            throw ProcessingException(it, e)
        }
    }
)