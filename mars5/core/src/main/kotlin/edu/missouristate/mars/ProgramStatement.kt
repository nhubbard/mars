package edu.missouristate.mars

import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.InstructionSourceMap.SourceOperand
import edu.missouristate.mars.assembler.Symbol
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.assembler.Token
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.core.AssemblyContext
import edu.missouristate.mars.core.SimulationSettings
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.util.NumberDisplayBase
import java.util.List.copyOf as copyList
import java.util.function.BiConsumer

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

/** One assembled or decoded instruction, with its optional source and operand provenance. */
open class ProgramStatement private constructor(
    open val sourceMIPSprogram: MIPSprogram?,
    private val detachedSourceFile: String?,
    open var source: String?,
    open val originalTokenList: TokenList?,
    open val strippedTokenList: TokenList?,
    open val instruction: Instruction?,
    open val address: Int,
    open val sourceLine: Int,
    open val instructionOrigin: Origin?,
    open val sourceOperands: List<SourceOperand>,
    private val displaySettings: SimulationSettings?,
    private val operandValues: IntArray?,
    private var numOperands: Int,
    open var binaryStatement: Int,
    open var basicAssemblyStatement: String?,
    open var machineStatement: String?,
    private val display: ArrayList<DisplayElement>,
) {
    /** Creates a statement with written tokens and source information. */
    @JvmOverloads
    constructor(
        sourceMIPSprogram: MIPSprogram?,
        source: String?,
        originalTokenList: TokenList?,
        strippedTokenList: TokenList?,
        instruction: Instruction?,
        textAddress: Int,
        sourceLine: Int,
        instructionOrigin: Origin? = null,
        sourceOperands: List<SourceOperand> = emptyList(),
    ) : this(
        sourceMIPSprogram, "", source, originalTokenList, strippedTokenList, instruction, textAddress, sourceLine,
        instructionOrigin, copyList(sourceOperands), sourceMIPSprogram?.assemblyContext()?.session?.settings,
        IntArray(4), 0, 0, null, null, ArrayList(),
    )

    /** Decodes an instruction when only the machine word and its address are available. */
    @JvmOverloads
    constructor(
        binaryStatement: Int,
        textAddress: Int,
        instructions: InstructionSet = InstructionDefaults.get(),
        displaySettings: SimulationSettings? = null,
    ) : this(binaryStatement, textAddress, instructions, displaySettings, instructions.findByBinaryCode(binaryStatement))

    private constructor(
        binary: Int, address: Int, instructions: InstructionSet, settings: SimulationSettings?, decoded: BasicInstruction?,
    ) : this(
        null, "", "", null, null, decoded ?: if (binary == 0) instructions.matchOperator("nop")!!.first() else null,
        address, 0, null, emptyList(), settings, if (decoded == null) null else IntArray(4),
        0, binary, null, null, ArrayList(),
    ) {
        if (decoded == null) {
            display.add(DisplayElement.Text("<INVALID>"))
        } else {
            decodeOperands(decoded, binary, address)
            describeDecodedInstruction(decoded)
        }
    }

    /** A detached copy of the instruction, safe to install in a different session. */
    open fun captureImage(): Image = Image.capture(this)

    class Image private constructor(statement: ProgramStatement) {
        private val mnemonic = statement.instruction!!.name
        private val format = statement.instruction!!.exampleFormat
        private val sourceFile = statement.sourceFile
        private val source = statement.source
        private val basicAssembly = statement.basicAssemblyStatement
        private val machine = statement.machineStatement
        private val address = statement.address
        private val line = statement.sourceLine
        private val binary = statement.binaryStatement
        private val numOperands = statement.numOperands
        private val operands = statement.operandValues!!.copyOf()
        private val origin = statement.instructionOrigin
        private val sourceOperands = statement.sourceOperands
        private val display = copyList(statement.display)

        fun load(instructions: InstructionSet, settings: SimulationSettings): ProgramStatement {
            val instruction = instructions.matchOperator(mnemonic)!!.first { it.exampleFormat == format }
            return ProgramStatement(
                null, sourceFile, source, null, null, instruction, address, line, origin,
                sourceOperands, settings, operands.copyOf(), numOperands, binary, basicAssembly, machine, ArrayList(display),
            )
        }

        fun hasSameMachineWord(other: Image): Boolean = address == other.address && binary == other.binary

        companion object {
            internal fun capture(statement: ProgramStatement): Image = Image(statement)
        }
    }

    open val sourceFile: String?
        get() {
            val program = sourceMIPSprogram
            return if (program == null) detachedSourceFile else program.filename
        }

    /** Invalid machine words have no decoded operands. */
    open fun getOperands(): IntArray? = operandValues

    /** Execution dispatches only decoded instructions. */
    internal val operands: IntArray get() = checkNotNull(getOperands())

    open fun getOperand(index: Int): Int = if (index in 0 until numOperands) operandValues!![index] else -1

    private fun assemblyContext(): AssemblyContext =
        checkNotNull(sourceMIPSprogram) { "Source program is required for assembly" }.assemblyContext()

    open fun buildBasicStatementFromBasicInstruction(errors: ErrorList) {
        buildBasicStatementFromBasicInstruction(errors) { _, _ -> }
    }

    /** Resolves operands and reports the written token for each resolved symbol. */
    open fun buildBasicStatementFromBasicInstruction(errors: ErrorList, recordSymbolUse: BiConsumer<Token, Symbol>) {
        val tokens = strippedTokenList!!
        val basic = StringBuilder()
        fun appendText(text: String) {
            basic.append(text)
            display.add(DisplayElement.Text(text))
        }

        appendText("${tokens[0].value} ")
        numOperands = 0
        for (index in 1 until tokens.size()) {
            val token = tokens[index]
            val value = token.value
            when (token.type) {
                TokenTypes.REGISTER_NUMBER, TokenTypes.REGISTER_NAME, TokenTypes.FP_REGISTER_NAME -> {
                    val register = when (token.type) {
                        TokenTypes.REGISTER_NUMBER -> {
                            appendText(value)
                            try {
                                assemblyContext().integerRegister(value)!!.number
                            } catch (_: Exception) {
                                errors.add(ErrorMessage(token, "invalid register name"))
                                return
                            }
                        }
                        TokenTypes.REGISTER_NAME -> {
                            val number = assemblyContext().integerRegisterNumber(value)
                            appendText("$$number")
                            number
                        }
                        else -> {
                            val number = assemblyContext().floatingRegisterNumber(value)
                            appendText("\$f$number")
                            number
                        }
                    }
                    if (token.type != TokenTypes.REGISTER_NUMBER && register < 0) {
                        val kind = if (token.type == TokenTypes.FP_REGISTER_NAME) "FPU " else ""
                        errors.add(ErrorMessage(token, "invalid ${kind}register name"))
                        return
                    }
                    operandValues!![numOperands++] = register
                }
                TokenTypes.IDENTIFIER -> {
                    val symbol = sourceMIPSprogram!!.localSymbolTable!!.getSymbolLocalOrGlobal(value)
                    if (symbol == null || symbol.address == SymbolTable.NOT_FOUND) {
                        errors.add(ErrorMessage(token, "Symbol \"$value\" not found in symbol table."))
                        return
                    }
                    recordSymbolUse.accept(token, symbol)
                    // Branch offsets are in words from the instruction after the branch.
                    val relative = (instruction as? BasicInstruction)?.instructionFormat == BasicInstructionFormat.I_BRANCH_FORMAT
                    val operand = if (relative) (symbol.address - (address + Instruction.INSTRUCTION_LENGTH)) shr 2 else symbol.address
                    basic.append(operand)
                    display.add(if (relative) DisplayElement.Value(operand) else DisplayElement.Address(operand))
                    operandValues!![numOperands++] = operand
                }
                TokenTypes.INTEGER_5, TokenTypes.INTEGER_16, TokenTypes.INTEGER_16U, TokenTypes.INTEGER_32 -> {
                    val operand = Binary.stringToInt(value)
                    basic.append(operand)
                    display.add(DisplayElement.Value(operand))
                    operandValues!![numOperands++] = operand
                }
                else -> appendText(value)
            }
            if (index < tokens.size() - 1 && !token.type.isParenthesis() && !tokens[index + 1].type.isParenthesis()) {
                appendText(",")
            }
        }
        basicAssemblyStatement = basic.toString()
    }

    open fun buildMachineStatementFromBasicStatement(errors: ErrorList) {
        val basic = instruction as? BasicInstruction
        if (basic == null) {
            errors.add(ErrorMessage(sourceMIPSprogram, sourceLine, 0, "INTERNAL ERROR: pseudo-instruction expansion contained a pseudo-instruction"))
            return
        }
        machineStatement = basic.operationMask.replace('c', '0')
        val values = operandValues!!
        if (basic.instructionFormat == BasicInstructionFormat.J_FORMAT) {
            if ((address and JUMP_REGION_MASK) != (values[0] and JUMP_REGION_MASK)) {
                errors.add(ErrorMessage(sourceMIPSprogram, sourceLine, 0, "Jump target word address beyond 26-bit range"))
                return
            }
            values[0] = values[0] ushr 2
            insertBinaryCode(values[0], Instruction.operandMask[0], errors)
        } else {
            for (index in 0 until numOperands) insertBinaryCode(values[index], Instruction.operandMask[index], errors)
        }
        binaryStatement = Binary.binaryStringToInt(machineStatement!!)
    }

    private fun insertBinaryCode(value: Int, mask: Char, errors: ErrorList) {
        val current = machineStatement!!
        val start = current.indexOf(mask)
        val end = current.lastIndexOf(mask)
        if (start < 0) {
            errors.add(ErrorMessage(sourceMIPSprogram, sourceLine, 0, "INTERNAL ERROR: mismatch in number of operands in statement vs mask"))
            return
        }
        machineStatement = current.replaceRange(start, end + 1, Binary.intToBinaryString(value, end - start + 1))
    }

    private fun decodeOperands(instruction: BasicInstruction, binary: Int, address: Int) {
        val mask = instruction.operationMask
        val values = operandValues!!
        for (code in "fst") {
            val first = mask.indexOf(code)
            if (first < 0) continue
            val low = 31 - mask.lastIndexOf(code)
            val high = 31 - first
            var operand = (binary shr low) and ((1 shl (high - low + 1)) - 1)
            if (instruction.instructionFormat == BasicInstructionFormat.I_BRANCH_FORMAT && numOperands == 2) {
                operand = (operand shl 16) shr 16
            } else if (instruction.instructionFormat == BasicInstructionFormat.J_FORMAT && numOperands == 0) {
                operand = operand or ((address shr 2) and 0x3c000000)
            }
            values[numOperands++] = operand
        }
    }

    private fun describeDecodedInstruction(instruction: BasicInstruction) {
        val tokens = instruction.tokenList
        val values = operandValues!!
        display.add(DisplayElement.Text("${instruction.name} "))
        var cursor = 1
        for (index in 0 until numOperands) {
            if (cursor > 1 && cursor < tokens.size() && !tokens[cursor].type.isParenthesis()) {
                display.add(DisplayElement.Text(","))
            }
            while (cursor < tokens.size()) {
                val token = tokens[cursor++]
                if (token.type.isParenthesis()) {
                    display.add(DisplayElement.Text(token.value))
                    continue
                }
                display.add(when (token.type) {
                    TokenTypes.FP_REGISTER_NAME -> DisplayElement.Text("\$f${values[index]}")
                    TokenTypes.REGISTER_NAME, TokenTypes.REGISTER_NUMBER -> DisplayElement.Text("$${values[index]}")
                    else -> DisplayElement.Value(values[index])
                })
                break
            }
        }
        while (cursor < tokens.size()) {
            val token = tokens[cursor++]
            if (token.type.isParenthesis()) display.add(DisplayElement.Text(token.value))
        }
    }

    /** Renders operands using the owning session's current display preferences. */
    open val printableBasicAssemblyStatement: String
        get() = buildString {
            val hexAddresses = displaySettings?.displayAddressesInHex() != false
            val hexValues = displaySettings?.displayValuesInHex() != false
            val addressBase = if (hexAddresses) NumberDisplayBase.HEXADECIMAL else NumberDisplayBase.DECIMAL
            for (element in display) {
                when (element) {
                    is DisplayElement.Text -> append(element.text)
                    is DisplayElement.Address -> append(NumberDisplayBase.formatNumber(element.address, addressBase))
                    is DisplayElement.Value -> append(if (hexValues) {
                        Binary.intToHexString(element.value)
                    } else NumberDisplayBase.formatNumber(element.value, NumberDisplayBase.DECIMAL))
                }
            }
        }

    override fun toString(): String = buildString {
        append('[').append(address).append(']')
        append(PADDING, 0, 16 - length)
        val assembly = basicAssemblyStatement
        if (assembly != null) {
            val firstSpace = assembly.indexOf(' ')
            append(assembly, 0, firstSpace)
            append(PADDING, 0, 24 - length)
            append(assembly.substring(firstSpace + 1))
        } else {
            append("0x").append(binaryStatement.toString(16))
        }
        append(PADDING, 0, 40 - length).append(";  ")
        val values = operandValues
        if (values != null) {
            for (index in 0 until numOperands) append(values[index].toString(16)).append(' ')
        }
        val binary = machineStatement
        if (binary != null) {
            append('[').append(Binary.binaryStringToHexString(binary)).append("]  ")
            append(binary, 0, 6)
            val divisions = intArrayOf(6, 11, 16, 21, 26, 32)
            for (index in 0 until divisions.lastIndex) {
                append('|').append(binary, divisions[index], divisions[index + 1])
            }
        }
    }

    private sealed interface DisplayElement {
        data class Text(val text: String) : DisplayElement
        data class Address(val address: Int) : DisplayElement
        data class Value(val value: Int) : DisplayElement
    }

    private fun TokenTypes.isParenthesis(): Boolean = this == TokenTypes.LEFT_PAREN || this == TokenTypes.RIGHT_PAREN

    private companion object {
        const val JUMP_REGION_MASK = -0x10000000
        const val PADDING = "                               "
    }
}
