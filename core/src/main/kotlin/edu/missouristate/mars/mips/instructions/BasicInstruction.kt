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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.mips.instructions

/**
 * BasicInstruction constructor.
 *
 * @param example     An example usage of the instruction, as a String.
 * @param description A description of the instruction to be shown to the user.
 * @param instrFormat The format is R, I, I-branch or J.
 * @param opcodeMask  The opcode mask is a 32-character string that contains the opcode in binary in the appropriate bit
 *                    positions and codes for operand positions ('f', 's', 't') in the remaining positions.
 * @param simCode     The inline definition of an object and class which anonymously implements the SimulationCode
 *                    interface.
 * @see SimulationCode
 */
open class BasicInstruction @JvmOverloads constructor(
    example: String,
    /** A description of the instruction for the end-user. */
    override var description: String = "",
    instrFormat: BasicInstructionFormat,
    opcodeMask: String,
    simCode: SimulationCode
) : Instruction() {
    /** The instruction name. */
    override var name = extractOperator(example)

    /** An example of the instruction format. */
    override var exampleFormat: String = example

    /**
     * The operand format of the instruction.
     *
     * MIPS defines three of the four supported formats:
     * - R-format is all registers.
     * - I-format is address formed from register base with immediate offset.
     * - J-format is for jump destination addresses.
     * - I-branch format, which is specific to MARS, for branch destination addresses. These are a variation of the
     *   I-format in that the computed value is address relative to the Program Counter.
     *
     * Static objects represent all four formats.
     */
    val instructionFormat: BasicInstructionFormat = instrFormat

    /**
     * The 32-character operation mask.
     * Each mask position represents a bit position in the 32-bit machine instruction.
     * Operation codes and unused bits are represented in the mask by 1's and 0's.
     * Operand codes are represented by 'f', 's', and 't' for bits occupied by first, second, and third operand,
     * respectively.
     */
    val operationMask = opcodeMask.replace(" ", "")

    /**
     * The code that simulates execution of the instruction.
     */
    val simulationCode: SimulationCode = simCode

    val opcodeMask: Int
    val opcodeMatch: Int

    /*
     * A Basic Primer on Operand Positions
     * -----------------------------------
     *
     * The codes for the operand positions are `f` for the first operand, `s` for the second operand, and `t` for the
     * third operand.
     *
     * For example, `add rd, rs, rt` is an R-format instruction with fields in this order:
     * opcode, rs, rt, rd, shamt, funct.
     *
     * The opcode is 0, shamt is 0, and funct is 0x40.
     * Based on the operand order, its mask is "000000ssssstttttfffff00000100000", split in this way:
     *
     * |--------|-------|-------|-------|-------|--------|
     * | opcode |  rs   |  rt   |  rd   | shamt | funct  |
     * |--------|-------|-------|-------|-------|--------|
     * | 000000 | sssss | ttttt | fffff | 00000 | 100000 |
     * |--------|-------|-------|-------|-------|--------|
     *
     * This mask can be used at code generation time to map the assembly component to its correct bit positions in the
     * binary machine instruction.
     *
     * It can also be used at runtime to match a binary machine instruction to the correct instruction simulator;
     * it needs to match all the zeroes and ones.
     */

    init {
        if (operationMask.length != INSTRUCTION_LENGTH_BITS)
            println("$example mask is not $INSTRUCTION_LENGTH_BITS bits!")
        this.opcodeMask = operationMask
            .replace("[01]".toRegex(), "1")
            .replace("[^01]".toRegex(), "0")
            .toLong(2)
            .toInt()
        this.opcodeMatch = operationMask
            .replace("[^1]".toRegex(), "0")
            .toLong(2)
            .toInt()
    }
}