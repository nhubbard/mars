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

package edu.missouristate.mars.mips.instructions.impl.math.integer

import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.SimulationCode
import edu.missouristate.mars.util.Binary

class CountLeadingOnes : BasicInstruction(
    "clo \$t1,\$t2",
    "Count number of leading ones: set \$t1 to the count of leading one bits in \$t2 starting at the most significant bit position",
    BasicInstructionFormat.R_FORMAT,
    "011100 sssss 00000 fffff 00000 100001",
    SimulationCode {
        // MIPS32 requires rd (first) operand to appear twice in machine code.
        // It has to be the same as rt (third) operand in machine code, but the
        // source statement does not have or permit a third operand.
        // In the machine code, rd and rt are adjacent, but my mask
        // substitution cannot handle adjacent placement of the same source
        // operand (e.g. "... sssss fffff fffff ...") because it would interpret
        // the mask to be the total length of both (10 bits).  I could code it
        // to have 3 operands then define a pseudo-instruction of two operands
        // to translate into this, but then both would show up in the instruction set
        // list and I don't want that.  So I will use the convention of Computer
        // Organization and Design 3rd Edition, Appendix A, and code the rt bits
        // as 0's.  The generated code does not match SPIM and would not run
        // on a real MIPS machine, but since I am providing no means of storing
        // the binary code that is not really an issue.
        val operands = it.getOperandsOrThrow()
        val value = RegisterFile.getValue(operands[1])
        var leadingOnes = 0
        var bitPosition = 31
        while (Binary.bitValue(value, bitPosition) == 1 && bitPosition >= 0) {
            leadingOnes++
            bitPosition--
        }
        RegisterFile.updateRegister(operands[0], leadingOnes)
    }
)