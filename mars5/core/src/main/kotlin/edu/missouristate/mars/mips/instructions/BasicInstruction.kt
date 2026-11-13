package edu.missouristate.mars.mips.instructions


/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** A single machine instruction with operand positions and its execution callback. */
open class BasicInstruction(
    example: String,
    description: String,
    open val instructionFormat: BasicInstructionFormat,
    operMask: String,
    open val simulationCode: SimulationCode,
) : Instruction(example, description) {
    /** Fixed bits are 0 or 1, operand bits are f/s/t, and ignored bits are c. */
    private val mask = operMask.replace(" ", "")
    open val operationMask: String get() = mask

    init {
        if (mask.length != INSTRUCTION_LENGTH_BITS) {
            println("$example mask not $INSTRUCTION_LENGTH_BITS bits!")
        }
    }

    /** Bits that must match a constant to decode this instruction. */
    open val opcodeMask: Int = mask.replace(fixedBit, "1").replace(nonBit, "0").toLong(2).toInt()

    /** The required bit values at the fixed positions. */
    open val opcodeMatch: Int = mask.replace(nonOne, "0").toLong(2).toInt()

    private companion object {
        val fixedBit = Regex("[01]")
        val nonBit = Regex("[^01]")
        val nonOne = Regex("[^1]")
    }

    constructor(
        example: String, instrFormat: BasicInstructionFormat, operMask: String, simCode: SimulationCode,
    ) : this(example, "", instrFormat, operMask, simCode)
}
