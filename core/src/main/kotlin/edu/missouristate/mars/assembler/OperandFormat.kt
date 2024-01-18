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

package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.Globals
import edu.missouristate.mars.CoreSettings
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.util.Binary.stringToInt

/**
 * Utility methods related to MIPS operand formats.
 */
object OperandFormat {
    /**
     * Syntax test for correct match in both numbers and types of operands.
     *
     * @param candidateList List of tokens generated from programmer's MIPS statement.
     * @param inst The (presumably best matched) MIPS instruction.
     * @param errors The ErrorList into which any error messages generated here will be added.
     * @return `true` if the programmer's statement matches the MIPS specification, `false` otherwise.
     */
    @JvmStatic
    fun tokenOperandMatch(candidateList: TokenList, inst: Instruction, errors: ErrorList): Boolean {
        if (!numOperandsCheck(candidateList, inst, errors)) return false
        return operandTypeCheck(candidateList, inst, errors)
    }

    /**
     * If the candidate operator token matches more than one instruction mnemonic,
     * then select the first mnemonic such that [Instruction] has an exact operand match.
     * If none match, return the first instruction and let the client deal with operand mismatches.
     */
    @JvmStatic
    fun bestOperandMatch(tokenList: TokenList, instrMatches: ArrayList<Instruction>?): Instruction? {
        if (instrMatches == null) return null
        if (instrMatches.size == 1) return instrMatches[0]
        return instrMatches.firstOrNull { tokenOperandMatch(tokenList, it, ErrorList()) } ?: instrMatches.first()
    }

    /**
     * Check to see if the numbers of operands are correct, and generate an error message if not.
     */
    @JvmStatic
    private fun numOperandsCheck(candidate: TokenList, spec: Instruction, errors: ErrorList): Boolean {
        val numOperands = candidate.size - 1
        val reqNumOperands = spec.tokenList.size - 1
        val operator = candidate.get(0)
        if (numOperands == reqNumOperands) return true
        else if (numOperands < reqNumOperands) generateMessage(operator, "Too few or incorrectly formatted operands. Expected: ${spec.exampleFormat}", errors)
        else generateMessage(operator, "Too many or incorrectly formatted operands. Expected: ${spec.exampleFormat}", errors)
        return false
    }

    /**
     * Generate an error message if the operand is not of the correct type for this operation and operand position.
     */
    @JvmStatic
    private fun operandTypeCheck(candidate: TokenList, spec: Instruction, errors: ErrorList): Boolean {
        var candidateToken: Token
        var specToken: Token
        var candidateType: TokenTypes
        var specType: TokenTypes
        for (i in 1..<spec.tokenList.size) {
            candidateToken = candidate[i]
            specToken = spec.tokenList[i]
            candidateType = candidateToken.type
            specType = specToken.type
            /*
            * A type mismatch is an error, unless:
            * (a) The spec calls for a register name, but the candidate provides a register number
            * (b) The spec calls for a register number, but the candidate provides a register name where permitted
            * (c) The spec calls for an integer of a specified maximum bit length, but the candidate is an integer of a
            * smaller bit length
            * (d) The spec calls for an identifier, but the candidate provides an operator, since operator names can be
            * used as labels
*
            * A type match is an error when the spec calls for a register name, but the candidate provides a register
            * name where names are not permitted.
            */
            if (specType == TokenTypes.IDENTIFIER && candidateType == TokenTypes.OPERATOR) {
                val replacement = Token(
                    TokenTypes.IDENTIFIER,
                    candidateToken.value,
                    candidateToken.sourceMipsProgram,
                    candidateToken.sourceLine,
                    candidateToken.startPosition
                )
                candidate.set(i, replacement)
                continue
            }
            if ((specType == TokenTypes.REGISTER_NAME || specType == TokenTypes.REGISTER_NUMBER) && candidateType == TokenTypes.REGISTER_NAME) {
                if (Globals.settings.getBooleanSetting(CoreSettings.BARE_MACHINE_ENABLED)) {
                    generateMessage(candidateToken, "Bare machine mode is enabled. You must use a register number instead of a name. Check the settings panel for more information.", errors)
                    return false
                } else continue
            }
            if (specType == TokenTypes.REGISTER_NAME && candidateType == TokenTypes.REGISTER_NUMBER) continue
            if ((specType == TokenTypes.INTEGER_16 && candidateType == TokenTypes.INTEGER_5) ||
                (specType == TokenTypes.INTEGER_16U && candidateType == TokenTypes.INTEGER_5) ||
                (specType == TokenTypes.INTEGER_32 && candidateType == TokenTypes.INTEGER_5) ||
                (specType == TokenTypes.INTEGER_32 && candidateType == TokenTypes.INTEGER_16U) ||
                (specType == TokenTypes.INTEGER_32 && candidateType == TokenTypes.INTEGER_16)
            ) continue
            if (candidateType == TokenTypes.INTEGER_16U || candidateType == TokenTypes.INTEGER_16) {
                val temp = stringToInt(candidateToken.value)
                if (specType == TokenTypes.INTEGER_16 && candidateType == TokenTypes.INTEGER_16U &&
                    temp >= DataTypes.MIN_HALF_VALUE && temp <= DataTypes.MAX_HALF_VALUE) continue
                if (specType == TokenTypes.INTEGER_16U && candidateType == TokenTypes.INTEGER_16 &&
                    temp >= DataTypes.MIN_UHALF_VALUE && temp <= DataTypes.MAX_UHALF_VALUE) continue
            }
            if ((specType == TokenTypes.INTEGER_5 && candidateType == TokenTypes.INTEGER_16) ||
                (specType == TokenTypes.INTEGER_5 && candidateType == TokenTypes.INTEGER_16U) ||
                (specType == TokenTypes.INTEGER_5 && candidateType == TokenTypes.INTEGER_32) ||
                (specType == TokenTypes.INTEGER_16 && candidateType == TokenTypes.INTEGER_16U) ||
                (specType == TokenTypes.INTEGER_16U && candidateType == TokenTypes.INTEGER_16) ||
                (specType == TokenTypes.INTEGER_16U && candidateType == TokenTypes.INTEGER_32) ||
                (specType == TokenTypes.INTEGER_16 && candidateType == TokenTypes.INTEGER_32)
            ) {
                generateMessage(candidateToken, "operand is out of range", errors)
                return false
            }
            if (candidateType != specType) {
                generateMessage(candidateToken, "operand is of incorrect type", errors)
                return false
            }
        }
        return true
    }

    /**
     * Handy utility for dealing with parsing errors.
     */
    @JvmStatic
    private fun generateMessage(token: Token, message: String, errors: ErrorList) {
        errors.add(ErrorMessage(token.sourceMipsProgram, token.sourceLine, token.startPosition, "\"${token.value}\": $message"))
    }
}