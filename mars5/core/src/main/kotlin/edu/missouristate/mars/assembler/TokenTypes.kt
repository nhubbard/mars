package edu.missouristate.mars.assembler

import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.util.Binary
import java.lang.Double.parseDouble

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

/** Token categories recognized by the MIPS assembler. */
enum class TokenTypes {
    COMMENT,
    DIRECTIVE,
    OPERATOR,
    REGISTER_NAME,
    REGISTER_NUMBER,
    FP_REGISTER_NAME,
    IDENTIFIER,
    LEFT_PAREN,
    RIGHT_PAREN,
    INTEGER_5,
    INTEGER_16,
    INTEGER_16U,
    INTEGER_32,
    REAL_NUMBER,
    QUOTED_STRING,
    PLUS,
    MINUS,
    COLON,
    ERROR,
    MACRO_PARAMETER,
    ;

    companion object {
        @JvmStatic
        @JvmOverloads
        fun matchTokenType(value: String, instructions: InstructionSet = InstructionDefaults.get()): TokenTypes {
            val first = value[0]
            if (first == '\'') return ERROR
            if (first == '#') return COMMENT
            if (value.length == 1) {
                when (first) {
                    '(' -> return LEFT_PAREN
                    ')' -> return RIGHT_PAREN
                    ':' -> return COLON
                    '+' -> return PLUS
                    '-' -> return MINUS
                }
            }
            if (Macro.tokenIsMacroParameter(value, false)) return MACRO_PARAMETER
            RegisterNames.integer(value)?.let { return if (it.name == value) REGISTER_NAME else REGISTER_NUMBER }
            if (RegisterNames.floating(value) != null) return FP_REGISTER_NAME

            val integer = try {
                Binary.stringToInt(value)
            } catch (_: NumberFormatException) {
                null
            }
            if (integer != null) {
                return when (integer) {
                    in 0..31 -> INTEGER_5
                    in DataTypes.MIN_UHALF_VALUE..DataTypes.MAX_UHALF_VALUE -> INTEGER_16U
                    in DataTypes.MIN_HALF_VALUE..DataTypes.MAX_HALF_VALUE -> INTEGER_16
                    else -> INTEGER_32
                }
            }
            try {
                parseDouble(value)
                return REAL_NUMBER
            } catch (_: NumberFormatException) {
                // Continue with nonnumeric spellings.
            }
            return when {
                instructions.matchOperator(value) != null -> OPERATOR
                first == '.' && Directives.matchDirective(value) != null -> DIRECTIVE
                first == '"' -> QUOTED_STRING
                isValidIdentifier(value) -> IDENTIFIER
                else -> ERROR
            }
        }

        @JvmStatic
        fun isIntegerTokenType(type: TokenTypes?): Boolean =
            type == INTEGER_5 || type == INTEGER_16 || type == INTEGER_16U || type == INTEGER_32

        @JvmStatic
        fun isFloatingTokenType(type: TokenTypes?): Boolean = type == REAL_NUMBER

        @JvmStatic
        fun startsDataContinuation(type: TokenTypes?): Boolean =
            type == PLUS || type == MINUS || type == QUOTED_STRING || type == IDENTIFIER ||
                isIntegerTokenType(type) || isFloatingTokenType(type)

        @JvmStatic
        fun isValidIdentifier(value: String): Boolean {
            val first = value[0]
            if (!first.isLetter() && !isIdentifierSymbol(first)) return false
            for (index in 1 until value.length) {
                val character = value[index]
                if (!character.isLetterOrDigit() && !isIdentifierSymbol(character)) return false
            }
            return true
        }

        private fun isIdentifierSymbol(character: Char): Boolean =
            character == '_' || character == '.' || character == '$'
    }
}
