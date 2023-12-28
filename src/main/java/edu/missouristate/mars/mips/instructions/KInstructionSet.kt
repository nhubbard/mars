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

package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.Settings
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.simulator.DelayedBranch
import edu.missouristate.mars.simulator.Exceptions
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * The list of Instruction objects, each of which represents a MIPS instruction.
 * The instruction may either be basic (translates into binary machine code) or
 * extended (translates into a sequence of one or more basic instructions).
 */
class KInstructionSet {
    val instructionList: ArrayList<Instruction> = arrayListOf()
    private lateinit var opcodeMatchMaps: ArrayList<MatchMap>
    private lateinit var syscallLoader: SyscallLoader

    private val isDelayedBranchingEnabled: Boolean
        get() = Globals.settings.getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)

    companion object {
        /**
         * Get the square root of a long value.
         */
        @JvmStatic
        private fun getLongSqrt(value: Double): Long =
            if (value < 0.0) {
                java.lang.Double.doubleToLongBits(Double.NaN)
            } else {
                java.lang.Double.doubleToLongBits(sqrt(value))
            }

        /**
         * Get the ints from a program statement's operands.
         */
        @JvmStatic
        @Throws(ProcessingException::class)
        private fun getInts(statement: ProgramStatement, x: Int, m: String): IntArray {
            val operands = statement.getOperands() ?: throw ProcessingException(statement, m)
            if (operands[x] % 2 == 1) throw ProcessingException(statement, m)
            return operands
        }
    }

    /**
     * Find an instruction by its binary code.
     */
    fun findByBinaryCode(binaryInstr: Int): BasicInstruction? {
        val matchMaps = opcodeMatchMaps
        for (matchMap in matchMaps) {
            val ret = matchMap.find(binaryInstr)
            if (ret != null) return ret
        }
        return null
    }

    /**
     * Add pseudo-instructions.
     */
    private fun addPseudoInstructions() {
        // Leading "/" prevents the package name from being prepended to the file path.
        javaClass.getResourceAsStream("/PseudoOps.txt")?.use { stream ->
            InputStreamReader(stream).use { streamReader ->
                BufferedReader(streamReader).use { `in` ->
                    try {
                        var line: String?
                        var pseudoOp: String
                        var template: StringBuilder
                        var firstTemplate: String?
                        var token: String
                        var description: String
                        var tokenizer: StringTokenizer
                        line = `in`.readLine()
                        while (line != null) {
                            // Skip over comments, lines that start with a space, and blank lines.
                            if (!line.startsWith("#") && !line.startsWith(" ") && line.isNotEmpty()) {
                                description = ""
                                tokenizer = StringTokenizer(line, "\t")
                                pseudoOp = tokenizer.nextToken()
                                template = StringBuilder()
                                firstTemplate = null
                                while (tokenizer.hasMoreTokens()) {
                                    token = tokenizer.nextToken()
                                    if (token.startsWith("#")) {
                                        // Optional description must be the last token in the line.
                                        description = token.substring(1)
                                        break
                                    }
                                    if (token.startsWith("COMPACT")) {
                                        // Has a second template for Compact (16-bit) memory config
                                        firstTemplate = template.toString()
                                        template = StringBuilder()
                                        continue
                                    }
                                    template.append(token)
                                    if (tokenizer.hasMoreTokens()) template.append("\n")
                                }
                                val inst = if (firstTemplate == null)
                                    ExtendedInstruction(pseudoOp, template.toString(), description)
                                else ExtendedInstruction(pseudoOp, firstTemplate, template.toString(), description)
                                instructionList.add(inst)
                            }
                            line = `in`.readLine()
                        }
                    } catch (e: IOException) {
                        println("Internal error: MIPS pseudo-instructions could not be loaded.")
                        exitProcess(1)
                    } catch (e: Exception) {
                        println("Error: Invalid MIPS pseudo-instruction specification.")
                        exitProcess(1)
                    }
                }
            }
        } ?: run {
            println("Error: MIPS pseudo-instruction file PseudoOps.txt not found!")
            exitProcess(1)
        }
    }

    /**
     * Given an operator name, return the corresponding Instruction object(s) from the instruction set.
     *
     * @param name The operator name.
     * @return The list of corresponding Instruction object(s), or `null` if none are found.
     */
    fun matchOperator(name: String): ArrayList<Instruction>? =
        arrayListOf(*instructionList.filter {
            it.name.equals(name, true)
        }.toTypedArray()).ifEmpty { null }

    /**
     * Given a string, return the Instruction(s) from the instruction set whose operator name prefix matches it.
     * Case-insensitive.
     * For example, "s" will match `sw`, `sh`, `sb`, etc.
     *
     * @param query The string to match against the instruction names.
     * @return An ArrayList of matching Instruction object(s), or null if none match.
     */
    fun prefixMatchOperator(query: String?): ArrayList<Instruction>? =
        query?.let { q ->
            arrayListOf(*instructionList.filter {
                it.name.lowercase().startsWith(q.lowercase())
            }.toTypedArray()).ifEmpty { null }
        }

    /**
     * Method to find and invoke a syscall given its service number. An object represents each syscall function
     * in an ArrayList. Each object is a class that implements Syscall or extends AbstractSyscall.
     *
     * @param number The syscall number to locate.
     * @param statement The ProgramStatement where the syscall is being called from.
     */
    @Throws(ProcessingException::class)
    private fun findAndSimulateSyscall(number: Int, statement: ProgramStatement) {
        syscallLoader.findSyscall(number)?.simulate(statement)
            ?: throw ProcessingException(
                statement,
                "Invalid or unimplemented syscall number $number!",
                Exceptions.SYSCALL_EXCEPTION
            )
    }

    /**
     * Method to process a successful branch condition.
     * **Do NOT use this with jump instructions!**
     * The branch operand is a relative displacement in words, whereas the jump operand is an absolute address in bytes.
     * Handles delayed branching if enabled.
     *
     * @param displacement The displacement operand from the instruction.
     */
    private fun processBranch(displacement: Int) {
        if (isDelayedBranchingEnabled) {
            // Register the branch target's absolute byte address.
            DelayedBranch.register(RegisterFile.programCounter.getValue() + (displacement shl 2))
        } else {
            // Decrement is necessary because the program counter has already been incremented
            RegisterFile.setProgramCounter(RegisterFile.programCounter.getValue() + (displacement shl 2))
        }
    }

    /**
     * Method to process a jump.
     * **Do NOT use this with branch instructions!**
     * The branch operand is a relative displacement in words,
     * whereas the jump operand is an absolute address in bytes.
     * This handles delayed branching if that setting is enabled.
     *
     * @param targetAddress The jump target's absolute byte address.
     */
    private fun processJump(targetAddress: Int) {
        if (isDelayedBranchingEnabled)
            DelayedBranch.register(targetAddress)
        else RegisterFile.setProgramCounter(targetAddress)
    }

    /**
     * Method to process storing the return address of an instruction in the given register.
     * This is used only by the "and link" instructions: `jal`, `jalr`, `bltzal`, and `bgezal`.
     * If delayed branching is disabled, the return address is the address of the next instruction (e.g., the current
     * PC value).
     * If delayed branching is enabled, the return address is the instruction following that, to skip over the delay
     * slot.
     *
     * @param register The register number to receive the return address.
     */
    private fun processReturnAddress(register: Int) {
        val extra = if (isDelayedBranchingEnabled) Instruction.INSTRUCTION_LENGTH else 0
        RegisterFile.updateRegister(register, RegisterFile.programCounter.getValue() + extra)
    }

    private class MatchMap(val mask: Int, val matchMap: HashMap<Int, Instruction>) : Comparable<MatchMap> {
        val maskLength: Int

        init {
            var k = 0
            var n = mask
            while (n != 0) {
                k++
                n = n and (n - 1)
            }
            maskLength = k
        }

        override fun equals(other: Any?): Boolean =
            other is MatchMap && mask == other.mask

        override fun hashCode(): Int = mask

        override fun compareTo(other: MatchMap): Int {
            var d = other.maskLength - maskLength
            if (d == 0) d = mask - other.mask
            return d
        }

        fun find(instr: Int): BasicInstruction? = matchMap[instr and mask] as? BasicInstruction
    }
}