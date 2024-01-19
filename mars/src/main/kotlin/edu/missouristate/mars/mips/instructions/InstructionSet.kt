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

package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.*
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.impl.Nop
import edu.missouristate.mars.mips.instructions.impl.branches.*
import edu.missouristate.mars.mips.instructions.impl.compare.*
import edu.missouristate.mars.mips.instructions.impl.conversion.*
import edu.missouristate.mars.mips.instructions.impl.jumps.*
import edu.missouristate.mars.mips.instructions.impl.logic.*
import edu.missouristate.mars.mips.instructions.impl.math.doubleprecision.*
import edu.missouristate.mars.mips.instructions.impl.math.integer.*
import edu.missouristate.mars.mips.instructions.impl.math.singleprecision.*
import edu.missouristate.mars.mips.instructions.impl.memory.*
import edu.missouristate.mars.mips.instructions.impl.move.*
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThan
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThanImmediate
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThanImmediateUnsigned
import edu.missouristate.mars.mips.instructions.impl.set.SetLessThanUnsigned
import edu.missouristate.mars.mips.instructions.impl.system.BreakWithCode
import edu.missouristate.mars.mips.instructions.impl.system.BreakWithoutCode
import edu.missouristate.mars.mips.instructions.impl.system.RunSyscall
import edu.missouristate.mars.mips.instructions.impl.traps.*
import edu.missouristate.mars.simulator.DelayedBranch
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.util.Binary
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * The list of Instruction objects, each of which represents a MIPS instruction.
 * The instruction may either be basic (translates into binary machine code) or
 * extended (translates into a sequence of one or more basic instructions).
 */
object InstructionSet {
    @JvmStatic
    val instructionList: ArrayList<Instruction> = arrayListOf()

    @JvmStatic
    private lateinit var opcodeMatchMaps: ArrayList<MatchMap>

    @JvmStatic
    private lateinit var syscallLoader: SyscallLoader

    @JvmStatic
    private val isDelayedBranchingEnabled: Boolean
        get() = Globals.settings.getBooleanSetting(CoreSettings.DELAYED_BRANCHING_ENABLED)

    /**
     * Add all instructions to the list. A given extended instruction may have more than one instruction object,
     * depending on how many formats it can have.
     */
    @JvmStatic
    fun populate() {
        instructionList.add(Nop())


        // Math instructions
        instructionList.add(Add())
        instructionList.add(Subtract())
        instructionList.add(AddImmediate())
        instructionList.add(AddUnsignedNoOverflow())
        instructionList.add(SubtractionUnsignedNoOverflow())
        instructionList.add(AddImmediateUnsignedNoOverflow())
        instructionList.add(Multiply())
        instructionList.add(MultiplyUnsigned())
        instructionList.add(MultiplyNoOverflow())
        instructionList.add(MultiplyAdd())
        instructionList.add(MultiplyAddUnsigned())
        instructionList.add(MultiplySubtract())
        instructionList.add(MultiplySubtractUnsigned())
        instructionList.add(Divide())
        instructionList.add(DivideUnsignedNoOverflow())


        // Math utility instructions
        instructionList.add(MoveFromHiRegister())
        instructionList.add(MoveFromLoRegister())
        instructionList.add(MoveToHiRegister())
        instructionList.add(MoveToLoRegister())


        // Logic instructions
        instructionList.add(And())
        instructionList.add(Or())
        instructionList.add(AndImmediate())
        instructionList.add(OrImmediate())
        instructionList.add(Nor())
        instructionList.add(Xor())
        instructionList.add(XorImmediate())
        instructionList.add(ShiftLeftLogical())
        instructionList.add(ShiftLeftLogicalVariable())
        instructionList.add(ShiftRightLogical())
        instructionList.add(ShiftRightArithmetic())
        instructionList.add(ShiftRightArithmeticVariable())
        instructionList.add(ShiftRightLogicalVariable())


        // Load and store instructions
        instructionList.add(LoadWord())
        instructionList.add(LoadLink())
        instructionList.add(LoadWordLeft())
        instructionList.add(LoadWordRight())
        instructionList.add(LoadUpperImmediate())
        instructionList.add(StoreWord())
        instructionList.add(StoreConditional())
        instructionList.add(StoreWordLeft())
        instructionList.add(StoreWordRight())
        instructionList.add(LoadByte())
        instructionList.add(LoadHalfWord())
        instructionList.add(LoadHalfWordUnsigned())
        instructionList.add(LoadByteUnsigned())
        instructionList.add(StoreByte())
        instructionList.add(StoreHalfByte())


        // Branch instructions
        instructionList.add(BranchIfEqual())
        instructionList.add(BranchNotEqual())
        instructionList.add(BranchGreaterEqualZero())
        instructionList.add(BranchGreaterEqualZeroAndLink())
        instructionList.add(BranchGreaterThanZero())
        instructionList.add(BranchLessEqualZero())
        instructionList.add(BranchLessThanZero())
        instructionList.add(BranchLessThanZeroAndLink())


        // Set instructions
        instructionList.add(SetLessThan())
        instructionList.add(SetLessThanUnsigned())
        instructionList.add(SetLessThanImmediate())
        instructionList.add(SetLessThanImmediateUnsigned())


        // Move instructions
        instructionList.add(MoveConditionalNotZero())
        instructionList.add(MoveConditionalZero())
        instructionList.add(MoveIfFloatConditionFlagZeroFalse())
        instructionList.add(MoveIfFloatConditionFlagFalse())
        instructionList.add(MoveIfFloatConditionFlagZeroTrue())
        instructionList.add(MoveIfFloatConditionFlagTrue())


        // System instructions
        instructionList.add(BreakWithCode())
        instructionList.add(BreakWithoutCode())
        instructionList.add(RunSyscall())


        // Jump instructions
        instructionList.add(Jump())
        instructionList.add(JumpRegister())
        instructionList.add(JumpAndLink())
        instructionList.add(JumpAndLinkRegister())
        instructionList.add(JumpAndLinkReturnAddress())


        // Count instructions
        instructionList.add(CountLeadingOnes())
        instructionList.add(CountLeadingZeroes())


        // Coprocessor 0 (interrupt/exception controller) move instructions
        instructionList.add(MoveFromCoprocessor0())
        instructionList.add(MoveToCoprocessor0())


        // Single-precision floating point math instructions
        instructionList.add(FloatAdd())
        instructionList.add(FloatSubtract())
        instructionList.add(FloatMultiply())
        instructionList.add(FloatDivide())
        instructionList.add(FloatSquareRoot())
        instructionList.add(FloatFloorToWord())
        instructionList.add(FloatCeilingToWord())
        instructionList.add(RoundFloatToWord())
        instructionList.add(TruncateFloatToWord())
        instructionList.add(FloatAbsoluteValue())


        // Double-precision floating point math instructions
        instructionList.add(DoubleAdd())
        instructionList.add(DoubleSubtract())
        instructionList.add(DoubleMultiply())
        instructionList.add(DoubleDivide())
        instructionList.add(DoubleSquareRoot())
        instructionList.add(DoubleFloorToWord())
        instructionList.add(DoubleCeilingToWord())
        instructionList.add(RoundDoubleToWord())
        instructionList.add(TruncateDoubleToWord())
        instructionList.add(DoubleAbsoluteValue())


        // FPU branch instructions
        instructionList.add(BranchFPUZeroFlagTrue())
        instructionList.add(BranchFPUFlagTrue())
        instructionList.add(BranchFPUZeroFlagFalse())
        instructionList.add(BranchFPUFlagFalse())


        // Single-precision floating point comparison instructions
        instructionList.add(FloatCompareEqual())
        instructionList.add(FloatCompareEqualCustomFlag())
        instructionList.add(FloatCompareLessThanOrEqual())
        instructionList.add(FloatCompareLessThanOrEqualCustomFlag())
        instructionList.add(FloatCompareLess())
        instructionList.add(FloatCompareLessCustomFlag())


        // Double-precision floating point comparison instructions
        instructionList.add(DoubleCompareEqual())
        instructionList.add(DoubleCompareEqualCustomFlag())
        instructionList.add(DoubleCompareLessThanOrEqual())
        instructionList.add(DoubleCompareLessThanOrEqualCustomFlag())
        instructionList.add(DoubleCompareLess())
        instructionList.add(DoubleCompareLessCustomFlag())


        // Floating-point conversion instructions
        instructionList.add(ConvertFloatToDouble())
        instructionList.add(ConvertWordToDouble())
        instructionList.add(ConvertDoubleToFloat())
        instructionList.add(ConvertWordToFloat())
        instructionList.add(ConvertDoubleToWord())
        instructionList.add(ConvertFloatToWord())


        // FPU move instructions
        instructionList.add(MoveDouble())
        instructionList.add(MoveDoubleIfConditionFlagZeroFalse())
        instructionList.add(MoveDoubleIfConditionFlagFalse())
        instructionList.add(MoveDoubleIfConditionFlagZeroTrue())
        instructionList.add(MoveDoubleIfConditionFlagTrue())
        instructionList.add(MoveDoubleIfRegisterNotZero())
        instructionList.add(MoveDoubleIfRegisterZero())
        instructionList.add(MoveFloat())
        instructionList.add(MoveFloatIfConditionFlagZeroFalse())
        instructionList.add(MoveFloatIfConditionFlagFalse())
        instructionList.add(MoveFloatIfConditionFlagZeroTrue())
        instructionList.add(MoveFloatIfConditionFlagTrue())
        instructionList.add(MoveFloatIfRegisterNotZero())
        instructionList.add(MoveFloatIfRegisterZero())
        instructionList.add(MoveFromCoprocessor1())
        instructionList.add(MoveToCoprocessor1())


        // FPU negation instructions
        instructionList.add(NegateDouble())
        instructionList.add(NegateFloat())


        // FPU load instructions
        instructionList.add(LoadWordIntoFPU())
        instructionList.add(LoadDwordIntoFPU())


        // FPU store instructions
        instructionList.add(StoreWordFromFPU())
        instructionList.add(StoreDwordFromFPU())


        // Trap and exception return instructions
        instructionList.add(TrapIfEqual())
        instructionList.add(TrapIfEqualImmediate())
        instructionList.add(TrapNotEqual())
        instructionList.add(TrapNotEqualImmediate())
        instructionList.add(TrapIfGreaterOrEqual())
        instructionList.add(TrapIfGreaterOrEqualUnsigned())
        instructionList.add(TrapIfGreaterOrEqualImmediate())
        instructionList.add(TrapIfGreaterOrEqualImmediateUnsigned())
        instructionList.add(TrapLessThan())
        instructionList.add(TrapLessThanUnsigned())
        instructionList.add(TrapLessThanImmediate())
        instructionList.add(TrapLessThanImmediateUnsigned())
        instructionList.add(ExceptionReturn())

        // Read pseudo-instruction specs from data file and add them to the instruction list
        addPseudoInstructions()

        // Create the list of syscalls
        syscallLoader = SyscallLoader()
        syscallLoader.loadSyscalls()

        // Create the token list from each example. The parser uses this to determine the user program's correct syntax.
        for (instruction in instructionList) instruction.createExampleTokenList()

        val maskMap = hashMapOf<Int, HashMap<Int, Instruction>>()
        val matchMaps = arrayListOf<MatchMap>()
        for (instruction in instructionList) {
            if (instruction is BasicInstruction) {
                val mask = instruction.opcodeMask
                val match = instruction.opcodeMatch
                var matchMap = maskMap[mask]
                if (matchMap == null) {
                    matchMap = hashMapOf()
                    maskMap[mask] = matchMap
                    matchMaps.add(MatchMap(mask, matchMap))
                }
                matchMap[match] = instruction
            }
        }
        matchMaps.sort()
        this.opcodeMatchMaps = matchMaps
    }

    /**
     * Get the square root of a long value.
     */
    @JvmStatic
    fun getLongSqrt(value: Double): Long =
        (if (value < 0.0) Double.NaN else sqrt(value)).toLongBits()

    /**
     * Get the ints from a program statement's operands.
     */
    @JvmStatic
    @Throws(ProcessingException::class)
    fun getEvenOperand(statement: ProgramStatement, index: Int, errorMessage: String): IntArray {
        val operands = statement.getOperands() ?: throw ProcessingException(statement, errorMessage)
        if (operands[index] % 2 == 1) throw ProcessingException(statement, errorMessage)
        return operands
    }

    /**
     * Method to process a successful branch condition.
     * **Do NOT use this with jump instructions!**
     * The branch operand is a relative displacement in words, whereas the jump operand is an absolute address in bytes.
     * Handles delayed branching if enabled.
     *
     * @param displacement The displacement operand from the instruction.
     */
    @JvmStatic
    fun processBranch(displacement: Int) {
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
    @JvmStatic
    fun processJump(targetAddress: Int) {
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
    @JvmStatic
    fun processReturnAddress(register: Int) {
        val extra = if (isDelayedBranchingEnabled) Instruction.INSTRUCTION_LENGTH else 0
        RegisterFile.updateRegister(register, RegisterFile.programCounter.getValue() + extra)
    }

    @JvmStatic
    fun compareUnsigned(operands: IntArray, first: Int, second: Int) {
        val condition = if (first > 0 && second >= 0 || first < 0 && second < 0)
            first < second else first >= 0
        RegisterFile.updateRegister(operands[0], condition.toInt())
    }

    /**
     * Find an instruction by its binary code.
     */
    @JvmStatic
    fun findByBinaryCode(binaryInstr: Int): BasicInstruction? {
        val matchMaps = opcodeMatchMaps
        for (matchMap in matchMaps) {
            val ret = matchMap.find(binaryInstr)
            if (ret != null) return ret
        }
        return null
    }

    @JvmStatic
    fun ProgramStatement.floatCompare(conditionFlag: Int = 0, comparator: (Float, Float) -> Boolean) {
        val operands = getOperandsOrThrow()
        val op1: Float
        val op2: Float
        if (conditionFlag == 0) {
            op1 = Coprocessor1.getValue(operands[0]).bitsToFloat()
            op2 = Coprocessor1.getValue(operands[1]).bitsToFloat()
        } else {
            op1 = Coprocessor1.getValue(operands[1]).bitsToFloat()
            op2 = Coprocessor1.getValue(operands[2]).bitsToFloat()
        }
        if (comparator(op1, op2)) Coprocessor1.setConditionFlag(conditionFlag)
        else Coprocessor1.clearConditionFlag(conditionFlag)
    }

    @JvmStatic
    fun ProgramStatement.doubleCompare(comparator: (Double, Double) -> Boolean) {
        val operands = getOperandsOrThrow()
        val conditionFlag: Int
        val op1: Double
        val op2: Double
        if (operands.size == 2) {
            conditionFlag = 0
            if (operands.any { o -> o % 2 == 1 }) throw ProcessingException(
                this,
                "All registers must be even-numbered!"
            )
            op1 = Binary.twoIntegersToLong(
                Coprocessor1.getValue(operands[0] + 1),
                Coprocessor1.getValue(operands[0])
            ).bitsToDouble()
            op2 = Binary.twoIntegersToLong(
                Coprocessor1.getValue(operands[1] + 1),
                Coprocessor1.getValue(operands[1])
            ).bitsToDouble()
        } else {
            conditionFlag = operands[0]
            if (operands.any { o -> o % 2 == 1 }) throw ProcessingException(
                this,
                "All registers must be even-numbered!"
            )
            op1 = Binary.twoIntegersToLong(
                Coprocessor1.getValue(operands[1] + 1),
                Coprocessor1.getValue(operands[1])
            ).bitsToDouble()
            op2 = Binary.twoIntegersToLong(
                Coprocessor1.getValue(operands[2] + 1),
                Coprocessor1.getValue(operands[2])
            ).bitsToDouble()
        }
        if (comparator(op1, op2)) Coprocessor1.setConditionFlag(conditionFlag)
        else Coprocessor1.clearConditionFlag(conditionFlag)
    }

    /**
     * Add pseudo-instructions.
     */
    @JvmStatic
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
    @JvmStatic
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
    @JvmStatic
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
    @JvmStatic
    @Throws(ProcessingException::class)
    fun findAndSimulateSyscall(number: Int, statement: ProgramStatement) {
        syscallLoader.findSyscall(number)?.simulate(statement)
            ?: throw ProcessingException(
                statement,
                "Invalid or unimplemented syscall number $number!",
                Exceptions.SYSCALL_EXCEPTION
            )
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