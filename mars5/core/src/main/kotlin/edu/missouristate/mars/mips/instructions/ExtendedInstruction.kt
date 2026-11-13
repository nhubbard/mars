package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.util.Binary
import java.util.StringTokenizer

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/** A pseudo-instruction that expands to an ordered sequence of basic instructions. */
open class ExtendedInstruction @JvmOverloads constructor(
    example: String,
    translation: String?,
    compactTranslation: String?,
    description: String,
    instructions: InstructionSet? = null,
) : Instruction(example, description) {
    init {
        createExampleTokenList(instructions)
    }

    private val standardTemplates = translationList(translation)
    private val compactTemplates = translationList(compactTranslation)
    open val basicIntructionTemplateList: ArrayList<String>? get() = standardTemplates
    open val compactBasicIntructionTemplateList: ArrayList<String>? get() = compactTemplates

    constructor(example: String, translation: String?, description: String) :
        this(example, translation, null, description)

    constructor(example: String, translation: String?) : this(example, translation, "")

    override val instructionLength: Int get() = getInstructionLength(false)
    open val compactInstructionLength: Int get() = getCompactInstructionLength(false)

    open fun getInstructionLength(delayedBranching: Boolean): Int =
        expansionLength(standardTemplates, delayedBranching)

    open fun getCompactInstructionLength(delayedBranching: Boolean): Int =
        expansionLength(compactTemplates, delayedBranching)

    open fun hasCompactTranslation(): Boolean = compactTemplates != null

    open fun branchesToLabel(): Boolean = hasLabelBranch(standardTemplates) ||
        hasLabelBranch(compactTemplates)

    private fun hasLabelBranch(templates: List<String>?): Boolean =
        templates?.any { template -> template.trimEnd { Character.isWhitespace(it) }.endsWith(" LAB") } == true

    private fun expansionLength(templates: List<String>?, delayedBranching: Boolean): Int =
        INSTRUCTION_LENGTH * (templates?.count { delayedBranching || !it.contains("DBNOP") } ?: 0)

    private fun translationList(translation: String?): ArrayList<String>? {
        if (translation.isNullOrEmpty()) return null
        val tokens = StringTokenizer(translation, "\n")
        return ArrayList<String>(tokens.countTokens()).apply {
            while (tokens.hasMoreTokens()) add(tokens.nextToken())
        }
    }

    companion object {
        /** Substitute template markers in their historical order, from specific forms to general forms. */
        @JvmStatic
        fun makeTemplateSubstitutions(program: MIPSprogram, template: String, tokens: TokenList): String {
            val context = program.assemblyContext()
            if (template.contains("DBNOP")) return if (context.settings().isDelayedBranchingEnabled) "nop" else ""

            var instruction = template
            fun number(marker: String, value: Int) {
                instruction = instruction.replace(marker, value.toString())
            }
            fun adjustedHigh(value: Int): Int = (value shr 16) + Binary.bitValue(value, 15)
            fun low(marker: String, value: Int) {
                val index = instruction.indexOf(marker)
                if (instruction.getOrNull(index + marker.length) == 'U') {
                    number(marker + "U", value and 0xffff)
                } else {
                    number(marker, value.toShort().toInt())
                }
            }
            fun withAddend(prefix: String, value: String, substitute: (String, Int) -> Unit) {
                val index = instruction.indexOf(prefix)
                if (index >= 0) {
                    val add = instruction[index + prefix.length] - '0'
                    substitute(prefix + add, parseNumber(value, add))
                }
            }

            for (position in 1 until tokens.size()) {
                val value = tokens[position].value
                instruction = instruction.replace("RG$position", value).replace("OP$position", value)

                withAddend("LH${position}P", value) { marker, address -> number(marker, adjustedHigh(address)) }
                if (instruction.contains("LH$position")) number("LH$position", adjustedHigh(parseNumber(value)))
                withAddend("LL${position}P", value) { marker, address -> number(marker, address.toShort().toInt()) }
                if (instruction.contains("LL$position")) low("LL$position", parseNumber(value))

                withAddend("VHL${position}P", value) { marker, immediate -> number(marker, immediate shr 16) }
                withAddend("VH${position}P", value) { marker, immediate -> number(marker, adjustedHigh(immediate)) }
                if (instruction.contains("VH$position")) number("VH$position", adjustedHigh(parseNumber(value)))
                withAddend("VL${position}P", value) { marker, immediate -> low(marker, immediate) }
                if (instruction.contains("VL$position")) low("VL$position", parseNumber(value))
                if (instruction.contains("VHL$position")) number("VHL$position", parseNumber(value) shr 16)
            }

            if (instruction.contains("LHL")) number("LHL", parseNumber(tokens[2].value) shr 16)
            fun addressWithOffset(add: Int = 0): Int = try {
                Binary.stringToInt(tokens[2].value) + Binary.stringToInt(tokens[4].value) + add
            } catch (_: NumberFormatException) {
                0
            }
            fun offsetMarker(prefix: String, substitute: (String, Int) -> Unit) {
                val index = instruction.indexOf(prefix)
                if (index >= 0) {
                    val add = instruction[index + prefix.length] - '0'
                    substitute(prefix + add, addressWithOffset(add))
                }
            }
            offsetMarker("LHPAP") { marker, address -> number(marker, adjustedHigh(address)) }
            if (instruction.contains("LHPA")) number("LHPA", adjustedHigh(addressWithOffset()))
            if (instruction.contains("LHPN")) number("LHPN", addressWithOffset() shr 16)
            offsetMarker("LLPP") { marker, address -> number(marker, address.toShort().toInt()) }
            if (instruction.contains("LLP")) low("LLP", addressWithOffset())

            val branchOffset = instruction.indexOf("BROFF")
            if (branchOffset >= 0) {
                if (branchOffset + 7 <= instruction.length) {
                    val marker = instruction.substring(branchOffset, branchOffset + 7)
                    val replacement = if (context.settings().isDelayedBranchingEnabled) marker[6] else marker[5]
                    instruction = instruction.replace(marker, replacement.toString())
                } else {
                    instruction = instruction.replace("BROFF", "BAD_PSEUDO_OP_SPEC")
                }
            }
            if (instruction.contains("NR")) {
                for (position in 1 until tokens.size()) {
                    val token = tokens[position].value
                    val integer = context.integerRegister(token)
                    if (integer != null) {
                        if (integer.number >= 0) instruction = instruction.replace("NR$position", "$${integer.number + 1}")
                    } else {
                        val floating = context.floatingRegisterNumber(token)
                        if (floating >= 0) instruction = instruction.replace("NR$position", "\$f${floating + 1}")
                    }
                }
            }
            if (instruction.contains("S32")) number("S32", 32 - parseNumber(tokens[tokens.size() - 1].value))
            if (instruction.contains("LAB")) {
                val label = tokens[tokens.size() - 1].value
                val symbol = program.localSymbolTable!!.getSymbolGivenAddressLocalOrGlobal(label)
                if (symbol != null) instruction = instruction.replaceFirst("LAB", symbol.name)
            }
            return instruction
        }

        private fun parseNumber(value: String, add: Int = 0): Int = try {
            Binary.stringToInt(value) + add
        } catch (_: NumberFormatException) {
            0
        }
    }
}
