package edu.missouristate.mars.assembler

import edu.missouristate.mars.api.MarsTarget
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
    VECTOR_REGISTER_NAME,
    IDENTIFIER,
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    INTEGER_5,
    INTEGER_16,
    INTEGER_16U,
    INTEGER_32,
    INTEGER_64,
    REAL_NUMBER,
    QUOTED_STRING,
    OBJECT_ATTRIBUTE,
    ADDRESS_RELOCATION,
    LOCAL_LABEL_CANDIDATE,
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
                    '[' -> return LEFT_BRACKET
                    ']' -> return RIGHT_BRACKET
                    ':' -> return COLON
                    '+' -> return PLUS
                    '-' -> return MINUS
                }
            }
            if (Macro.tokenIsMacroParameter(value, false)) return MACRO_PARAMETER
            RegisterNames.integer(value)?.let { return if (it.name == value) REGISTER_NAME else REGISTER_NUMBER }
            if (RegisterNames.floating(value) != null) return FP_REGISTER_NAME
            if (instructions.target == MarsTarget.MARS64 && RegisterNames.vector(value) != null) return VECTOR_REGISTER_NAME

            // Preserve JDK numeric spellings, including NaN, Infinity, Unicode digits,
            // and its ASCII whitespace trimming, while skipping parsers for ordinary names.
            if (first.isDigit() || first in "+-.NI" || first <= ' ') {
                if (instructions.target == MarsTarget.MARS64 && hasDoublewordSpelling(value)) return INTEGER_64
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
            }
            return when {
                instructions.matchOperator(value) != null -> OPERATOR
                first == '.' && Directives.matchDirective(value) != null -> DIRECTIVE
                first == '"' -> QUOTED_STRING
                first == '@' && Directives.isObjectAttribute(value) -> OBJECT_ATTRIBUTE
                instructions.target == MarsTarget.MARS64 && first.isDigit() && isLocalNumericReference(value) -> LOCAL_LABEL_CANDIDATE
                isValidIdentifier(value) -> IDENTIFIER
                else -> ERROR
            }
        }

        @JvmStatic
        fun isIntegerTokenType(type: TokenTypes?): Boolean =
            type == INTEGER_5 || type == INTEGER_16 || type == INTEGER_16U || type == INTEGER_32

        fun isLocalNumericReference(value: String): Boolean = LocalNumericLabels.referenceNumber(value) != null

        @JvmStatic
        fun isFloatingTokenType(type: TokenTypes?): Boolean = type == REAL_NUMBER

        @JvmStatic
        fun startsDataContinuation(type: TokenTypes?): Boolean =
            type == PLUS || type == MINUS || type == QUOTED_STRING || type == IDENTIFIER || type == INTEGER_64 ||
                isIntegerTokenType(type) || isFloatingTokenType(type)

        private fun hasDoublewordSpelling(value: String): Boolean {
            val unsigned = value.removePrefix("-").removePrefix("+")
            if (unsigned.startsWith("0x", ignoreCase = true)) {
                if (unsigned.length !in 11..18) return false
                val magnitude = unsigned.substring(2).toULongOrNull(16) ?: return false
                // Keep padded signed literals accepted by classic word instructions.
                return !value.startsWith('-') || magnitude > 0x80000000uL
            }
            if (unsigned.firstOrNull() == '0' || unsigned.length < 10) return false
            val bits = value.toLongOrNull()
            return if (bits != null) bits !in Int.MIN_VALUE.toLong()..UInt.MAX_VALUE.toLong()
            else !value.startsWith('-') && unsigned.toULongOrNull() != null
        }

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
