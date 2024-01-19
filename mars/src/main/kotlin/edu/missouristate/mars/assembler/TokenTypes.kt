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

package edu.missouristate.mars.assembler

import edu.missouristate.mars.Globals
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.util.Binary

enum class TokenTypes(private val descriptor: String) {
    COMMENT("COMMENT"),
    DIRECTIVE("DIRECTIVE"),
    OPERATOR("OPERATOR"),
    DELIMITER("DELIMITER"),
    REGISTER_NAME("REGISTER_NAME"),
    REGISTER_NUMBER("REGISTER_NUMBER"),
    FP_REGISTER_NAME("FP_REGISTER_NAME"),
    IDENTIFIER("IDENTIFIER"),
    LEFT_PAREN("LEFT_PAREN"),
    RIGHT_PAREN("RIGHT_PAREN"),
    INTEGER_5("INTEGER_5"),
    INTEGER_16("INTEGER_16"),
    INTEGER_16U("INTEGER_16U"),
    INTEGER_32("INTEGER_32"),
    REAL_NUMBER("REAL_NUMBER"),
    QUOTED_STRING("QUOTED_STRING"),
    PLUS("PLUS"),
    MINUS("MINUS"),
    COLON("COLON"),
    ERROR("ERROR"),
    MACRO_PARAMETER("MACRO_PARAMETER");

    override fun toString(): String = descriptor

    val isIntegerTokenType get() = this in listOf(INTEGER_5, INTEGER_16U, INTEGER_16, INTEGER_32)
    val isFloatTokenType get() = this == REAL_NUMBER

    companion object {
        const val TOKEN_DELIMITERS = "\t ,()"

        /**
         * Classifies the given token into one of the MIPS types.
         *
         * @param value The String containing the language element, extracted from the MIPS program.
         * @return The corresponding TokenTypes entry if the parameter matches a defined MIPS token type, or `null`.
         */
        @JvmStatic
        fun matchTokenType(value: String): TokenTypes? {
            // If it starts with a single quote, it is a malformed character literal
            // because a well-formed character literal was converted to a stringified integer before getting here.
            if (value[0] == '\'') return TokenTypes.ERROR
            // Check if it's a comment.
            if (value[0] == '#') return TokenTypes.COMMENT
            // Check if it's one of the simple tokens.
            if (value.length == 1) {
                when (value[0]) {
                    '(' -> return TokenTypes.LEFT_PAREN
                    ')' -> return TokenTypes.RIGHT_PAREN
                    ':' -> return TokenTypes.COLON
                    '+' -> return TokenTypes.PLUS
                    '-' -> return TokenTypes.MINUS
                }
            }
            // Check if it's a macro parameter.
            if (Macro.tokenIsMacroParameter(value, false)) return TokenTypes.MACRO_PARAMETER
            // Check if it's a register.
            RegisterFile.getUserRegister(value)?.let {
                return if (it.name == value) TokenTypes.REGISTER_NAME else TokenTypes.REGISTER_NUMBER
            }
            // Check if it's a floating point register.
            Coprocessor1.getRegister(value)?.let {
                return TokenTypes.FP_REGISTER_NAME
            }
            // Check if it's an immediate/constant integer value.
            // Classified based on the number of bits needed to represent the number in binary.
            // This is necessary only because most immediate operands are limited to 16 bits, while others are limited
            // to 5 bits unsigned (shift amounts), and still others 32 bits.
            try {
                val i = Binary.stringToInt(value)
                // A large block comment detailing rescinded modifications dating back to 2008 has been removed.
                if (i in 0..31) return TokenTypes.INTEGER_5
                if (i in DataTypes.MIN_UHALF_VALUE..DataTypes.MAX_UHALF_VALUE) return TokenTypes.INTEGER_16U
                if (i in DataTypes.MIN_HALF_VALUE..DataTypes.MAX_HALF_VALUE) return TokenTypes.INTEGER_16
                // This is the default value if no other type is applicable.
                return TokenTypes.INTEGER_32
            } catch (ignored: NumberFormatException) {}
            // See if the value is a real (fixed or floating point) number.
            // Note that parseDouble() accepts integer values, but if it is an integer literal, this would not handle
            // integer values.
            value.toDoubleOrNull()?.let { return TokenTypes.REAL_NUMBER }
            // See if it is an instruction operator.
            if (Globals.instructionSet.matchOperator(value) != null) return TokenTypes.OPERATOR
            // See if it is a directive.
            if (value[0] == '.' && Directives.matchDirective(value) != null) return TokenTypes.DIRECTIVE
            // See if it is a quoted string.
            if (value[0] == '"') return TokenTypes.QUOTED_STRING
            // Test for identifiers goes last, because there are tokens for various MIPS constructs, such as operators
            // and directives, that could also fit the lexical specifications of an identifier, and those need to be
            // recognized first.
            if (value.isValidIdentifier()) return TokenTypes.IDENTIFIER
            // There is no valid match.
            return TokenTypes.ERROR
        }

        @JvmStatic
        fun String.isValidIdentifier(): Boolean {
            var result = this[0].isLetter() || this[0] == '_' || this[0] == '.' || this[0] == '$'
            var index = 1
            while (result && index < length) {
                if (!(this[index].isLetterOrDigit() || this[index] == '_' || this[index] == '.' || this[index] == '$'))
                    result = false
                index++
            }
            return result
        }
    }
}