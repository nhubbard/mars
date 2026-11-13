package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.util.Binary

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

/** Matches written operands to instruction forms, preserving source diagnostics. */
object OperandFormat {
    @JvmStatic
    fun matches(tokens: TokenList, instruction: Instruction): Boolean =
        tokenOperandMatch(tokens.clone() as TokenList, instruction, ErrorList())

    @JvmStatic
    fun matchesOperands(tokens: TokenList, instruction: Instruction, firstOperand: Int, endOperand: Int, bareMachine: Boolean): Boolean =
        firstOperand in 1..<endOperand && endOperand <= tokens.size() && endOperand <= instruction.tokenList.size() &&
            operandTypeCheck(tokens.clone() as TokenList, instruction, ErrorList(), bareMachine, firstOperand, endOperand)

    @JvmStatic
    @JvmOverloads
    fun tokenOperandMatch(candidate: TokenList, instruction: Instruction, errors: ErrorList, bareMachine: Boolean = false): Boolean =
        numOperandsCheck(candidate, instruction, errors) && operandTypeCheck(candidate, instruction, errors, bareMachine, 1, candidate.size())

    @JvmStatic
    @JvmOverloads
    fun bestOperandMatch(tokens: TokenList, matches: ArrayList<Instruction>?, bareMachine: Boolean = false): Instruction? {
        if (matches == null) return null
        if (matches.size == 1) return matches.first()
        return matches.firstOrNull { tokenOperandMatch(tokens, it, ErrorList(), bareMachine) } ?: matches.first()
    }

    private fun numOperandsCheck(candidate: TokenList, instruction: Instruction, errors: ErrorList): Boolean {
        val written = candidate.size() - 1
        val expected = instruction.tokenList.size() - 1
        val operator = candidate[0]
        if (written == expected) return true
        val amount = if (written < expected) "few" else "many"
        report(operator, "Too $amount or incorrectly formatted operands. Expected: ${instruction.exampleFormat}", errors)
        return false
    }

    private fun operandTypeCheck(
        candidate: TokenList, instruction: Instruction, errors: ErrorList, bareMachine: Boolean, firstOperand: Int, endOperand: Int,
    ): Boolean {
        for (index in firstOperand until endOperand) {
            val written = candidate[index]
            val type = written.type
            val expected = instruction.tokenList[index].type
            if (expected == TokenTypes.IDENTIFIER && type == TokenTypes.OPERATOR) {
                candidate[index] = written.withType(TokenTypes.IDENTIFIER)
                continue
            }
            if ((expected == TokenTypes.REGISTER_NAME || expected == TokenTypes.REGISTER_NUMBER) && type == TokenTypes.REGISTER_NAME) {
                if (bareMachine) {
                    report(written, "Use register number instead of name.  See Settings.", errors)
                    return false
                }
                continue
            }
            if (expected == TokenTypes.REGISTER_NAME && type == TokenTypes.REGISTER_NUMBER) continue
            if (expected == TokenTypes.INTEGER_16 && type == TokenTypes.INTEGER_5 ||
                expected == TokenTypes.INTEGER_16U && type == TokenTypes.INTEGER_5 ||
                expected == TokenTypes.INTEGER_32 && (type == TokenTypes.INTEGER_5 || type == TokenTypes.INTEGER_16U || type == TokenTypes.INTEGER_16)
            ) continue
            if (type == TokenTypes.INTEGER_16U || type == TokenTypes.INTEGER_16) {
                val value = Binary.stringToInt(written.value)
                if (expected == TokenTypes.INTEGER_16 && type == TokenTypes.INTEGER_16U && value in DataTypes.MIN_HALF_VALUE..DataTypes.MAX_HALF_VALUE) continue
                if (expected == TokenTypes.INTEGER_16U && type == TokenTypes.INTEGER_16 && value in DataTypes.MIN_UHALF_VALUE..DataTypes.MAX_UHALF_VALUE) continue
            }
            if (expected == TokenTypes.INTEGER_5 && (type == TokenTypes.INTEGER_16 || type == TokenTypes.INTEGER_16U || type == TokenTypes.INTEGER_32) ||
                expected == TokenTypes.INTEGER_16 && (type == TokenTypes.INTEGER_16U || type == TokenTypes.INTEGER_32) ||
                expected == TokenTypes.INTEGER_16U && (type == TokenTypes.INTEGER_16 || type == TokenTypes.INTEGER_32)
            ) {
                report(written, "operand is out of range", errors)
                return false
            }
            if (type != expected) {
                report(written, "operand is of incorrect type", errors)
                return false
            }
        }
        return true
    }

    private fun report(token: Token, message: String, errors: ErrorList) {
        errors.add(ErrorMessage(token, "\"${token.value}\": $message"))
    }
}
