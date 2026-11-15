package edu.missouristate.mars

import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.InstructionSourceMap.SourceOperand
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.assembler.AddressRelocation
import edu.missouristate.mars.assembler.LocalNumericReference
import edu.missouristate.mars.assembler.Symbol
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.assembler.Token
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.core.AssemblyContext
import edu.missouristate.mars.core.SimulationSettings
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.util.NumberDisplayBase
import java.util.List.copyOf as copyList

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
    val addressBits: Long = address.toLong(),
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
        addressBits: Long = textAddress.toLong(),
    ) : this(
        sourceMIPSprogram, "", source, originalTokenList, strippedTokenList, instruction, textAddress, sourceLine,
        instructionOrigin, copyList(sourceOperands), sourceMIPSprogram?.assemblyContext()?.session?.settings,
        IntArray(4), 0, 0, null, null, ArrayList(), addressBits,
    )

    /** Decodes an instruction when only the machine word and its address are available. */
    @JvmOverloads
    constructor(
        binaryStatement: Int,
        textAddress: Int,
        instructions: InstructionSet = InstructionDefaults.get(),
        displaySettings: SimulationSettings? = null,
    ) : this(binaryStatement, textAddress, instructions, displaySettings, instructions.findByBinaryCode(binaryStatement))

    constructor(binary: Int, address: Long, instructions: InstructionSet, settings: SimulationSettings? = null) :
        this(binary, address.toInt(), instructions, settings, instructions.findByBinaryCode(binary), address)

    private constructor(
        binary: Int, address: Int, instructions: InstructionSet, settings: SimulationSettings?, decoded: BasicInstruction?,
        addressBits: Long = address.toLong(),
    ) : this(
        null, "", "", null, null, decoded ?: if (binary == 0) instructions.matchOperator("nop")!!.first() else null,
        address, 0, null, emptyList(), settings, if (decoded == null) null else IntArray(4),
        0, binary, null, null, ArrayList(), addressBits,
    ) {
        if (decoded == null) {
            display.add(DisplayElement.Text("<INVALID>"))
        } else {
            decodeOperands(decoded, binary, address)
            describeDecodedInstruction(decoded)
        }
    }

    private var absoluteJumpAddress: Long? = null

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
        private val addressBits = statement.addressBits
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
                sourceOperands, settings, operands.copyOf(), numOperands, binary, basicAssembly, machine, ArrayList(display), addressBits,
            )
        }

        fun hasSameMachineWord(other: Image): Boolean = addressBits == other.addressBits && binary == other.binary

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

    /** The encoded destination of a relative branch, independent of its execution outcome. */
    fun relativeBranchDestination(): Long? {
        val basic = instruction as? BasicInstruction ?: return null
        if (basic.instructionFormat != BasicInstructionFormat.I_BRANCH_FORMAT || numOperands == 0) return null
        val displacement = operands[numOperands - 1]
        return addressBits + Instruction.INSTRUCTION_LENGTH + (displacement.toLong() shl 2)
    }

    /** Execution dispatches only decoded instructions. */
    internal val operands: IntArray get() = checkNotNull(getOperands())

    private fun assemblyContext(): AssemblyContext =
        checkNotNull(sourceMIPSprogram) { "Source program is required for assembly" }.assemblyContext()

    open fun buildBasicStatementFromBasicInstruction(errors: ErrorList) {
        buildBasicStatementFromBasicInstruction(errors) { _, _ -> }
    }

    /** Resolves operands and reports the written token for each resolved symbol. */
    open fun buildBasicStatementFromBasicInstruction(errors: ErrorList, recordSymbolUse: (Token, Symbol) -> Unit) {
        val tokens = strippedTokenList!!
        val basic = StringBuilder()
        fun appendText(text: String) {
            basic.append(text)
            display.add(DisplayElement.Text(text))
        }
        fun resolveSymbol(token: Token): Symbol? {
            val symbol = if (token is LocalNumericReference) token.symbol
            else sourceMIPSprogram!!.localSymbolTable!!.getSymbolLocalOrGlobal(token.value)
            if (symbol == null || symbol.addressBits == SymbolTable.NOT_FOUND.toLong()) {
                val message = if (token is LocalNumericReference) "Numeric label reference \"${token.value}\" has no matching definition."
                else "Symbol \"${token.value}\" not found in symbol table."
                errors.add(ErrorMessage(token, message))
                return null
            }
            recordSymbolUse(token, symbol)
            return symbol
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
                            appendText($$"$f$$number")
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
                TokenTypes.VECTOR_REGISTER_NAME -> {
                    appendText(value)
                    operandValues!![numOperands++] = checkNotNull(RegisterNames.vector(value))
                }
                TokenTypes.IDENTIFIER -> {
                    val symbol = resolveSymbol(token) ?: return
                    // Branch offsets are in words from the instruction after the branch.
                    val basicInstruction = instruction as? BasicInstruction
                    val relative = basicInstruction?.instructionFormat == BasicInstructionFormat.I_BRANCH_FORMAT
                    val target = symbol.addressBits
                    if (target != target.toInt().toLong() && !relative &&
                        (instruction as? BasicInstruction)?.instructionFormat != BasicInstructionFormat.J_FORMAT &&
                        (instruction as? ExtendedInstruction)?.acceptsWideAddress != true
                    ) {
                        errors.add(ErrorMessage(token, "Label address requires a full-width operand. Load it with la or dla and use register addressing."))
                        return
                    }
                    val wideTarget = sourceMIPSprogram?.assemblyContext()?.session?.target == MarsTarget.MARS64
                    val displacement = target - (addressBits + Instruction.INSTRUCTION_LENGTH)
                    val operand = if (!relative) target else if (wideTarget) displacement shr 2 else (displacement.toInt() shr 2).toLong()
                    if (relative && wideTarget) {
                        val mask = basicInstruction.operationMask
                        val width = mask.reversed().takeWhile { it == mask.last() }.length
                        val limit = 1L shl (width - 1)
                        if (operand !in -limit..<limit || target and 3 != 0L) {
                            errors.add(ErrorMessage(token, "Branch destination is unaligned or outside its signed displacement range"))
                            return
                        }
                    }
                    if ((instruction as? BasicInstruction)?.instructionFormat == BasicInstructionFormat.J_FORMAT) {
                        absoluteJumpAddress = target
                    }
                    basic.append(if (!relative && target != target.toInt().toLong()) Binary.longToHexString(target) else operand)
                    display.add(if (relative) DisplayElement.Value(operand.toInt()) else DisplayElement.Address(operand.toInt()))
                    operandValues!![numOperands++] = operand.toInt()
                }
                TokenTypes.ADDRESS_RELOCATION -> {
                    val relocation = token as AddressRelocation
                    val symbol = resolveSymbol(relocation.symbol) ?: return
                    val field = relocation.part.field(symbol.addressBits + relocation.addend)
                    basic.append(field)
                    display.add(DisplayElement.Value(field))
                    operandValues!![numOperands++] = field
                }
                TokenTypes.INTEGER_5, TokenTypes.INTEGER_16, TokenTypes.INTEGER_16U, TokenTypes.INTEGER_32 -> {
                    val operand = Binary.stringToInt(value)
                    basic.append(if (instruction?.name == "dli") value else operand)
                    display.add(DisplayElement.Value(operand))
                    operandValues!![numOperands++] = operand
                }
                else -> appendText(value)
            }
            if (index < tokens.size() - 1 && !token.type.isParenthesis() && token.type != TokenTypes.LEFT_BRACKET &&
                !tokens[index + 1].type.isParenthesis() &&
                tokens[index + 1].type != TokenTypes.LEFT_BRACKET && tokens[index + 1].type != TokenTypes.RIGHT_BRACKET) {
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
        val values = operandValues!!
        if (basic.instructionFormat == BasicInstructionFormat.J_FORMAT) {
            machineStatement = basic.operationMask.replace('c', '0')
            val target = absoluteJumpAddress ?: values[0].toLong()
            val regionMask = JUMP_REGION_MASK.toLong()
            val pc = if (sourceMIPSprogram?.assemblyContext()?.session?.target == MarsTarget.MARS64) {
                addressBits + Instruction.INSTRUCTION_LENGTH
            } else addressBits
            if (pc and regionMask != target and regionMask) {
                errors.add(ErrorMessage(sourceMIPSprogram, sourceLine, 0, "Jump target word address beyond 26-bit range"))
                return
            }
            values[0] = values[0] ushr 2
            insertBinaryCode(values[0], Instruction.operandMask[0], errors)
        } else {
            val encoded = try {
                basic.encodeOperands(values)
            } catch (failure: IllegalArgumentException) {
                errors.add(ErrorMessage(this, failure.message))
                return
            }
            machineStatement = basic.encodingMask(values).replace(" ", "").replace('c', '0')
            for (index in 0 until basic.encodedOperandCount(numOperands)) {
                insertBinaryCode(encoded[index], Instruction.operandMask[index], errors)
            }
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
        for (code in Instruction.operandMask) {
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
        instruction.decodeOperands(values)
        numOperands = instruction.decodedOperandCount(numOperands)
    }

    private fun describeDecodedInstruction(instruction: BasicInstruction) {
        val tokens = instruction.tokenList
        val values = operandValues!!
        display.add(DisplayElement.Text("${instruction.name} "))
        var cursor = 1
        for (index in 0 until numOperands) {
            if (cursor > 1 && cursor < tokens.size() && !tokens[cursor].type.isParenthesis() && tokens[cursor].type != TokenTypes.LEFT_BRACKET) {
                display.add(DisplayElement.Text(","))
            }
            while (cursor < tokens.size()) {
                val token = tokens[cursor++]
                if (token.type.isParenthesis() || token.type == TokenTypes.LEFT_BRACKET) {
                    display.add(DisplayElement.Text(token.value))
                    continue
                }
                display.add(when (token.type) {
                    TokenTypes.VECTOR_REGISTER_NAME -> DisplayElement.Text($$"$w$${values[index]}")
                    TokenTypes.FP_REGISTER_NAME -> DisplayElement.Text($$"$f$${values[index]}")
                    TokenTypes.REGISTER_NAME, TokenTypes.REGISTER_NUMBER -> DisplayElement.Text("$${values[index]}")
                    else -> if (tokens[cursor - 2].type == TokenTypes.LEFT_BRACKET) {
                        DisplayElement.Text(values[index].toString())
                    } else DisplayElement.Value(values[index])
                })
                if (cursor < tokens.size() && tokens[cursor].type == TokenTypes.RIGHT_BRACKET) {
                    display.add(DisplayElement.Text(tokens[cursor++].value))
                }
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
