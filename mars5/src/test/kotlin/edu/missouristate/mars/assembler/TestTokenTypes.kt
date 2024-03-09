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

import edu.missouristate.mars.Globals
import edu.missouristate.mars.assembler.TokenTypes.Companion.isValidIdentifier
import edu.missouristate.mars.twoArgumentsOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestTokenTypes {
    @BeforeAll
    fun beforeAll() {
        Globals.initialize()
    }

    @ParameterizedTest
    @MethodSource("matchTokenTypeSource")
    fun testMatchTokenType(value: String, expectedType: TokenTypes) {
        assertEquals(expectedType, TokenTypes.matchTokenType(value))
    }

    @ParameterizedTest
    @MethodSource("isIntegerTokenTypeSource")
    fun testIsIntegerTokenType(type: TokenTypes, expectedResult: Boolean) {
        assertEquals(expectedResult, type.isIntegerTokenType)
    }

    @ParameterizedTest
    @MethodSource("isFloatingTokenTypeSource")
    fun testIsFloatingTokenType(type: TokenTypes, expectedResult: Boolean) {
        assertEquals(expectedResult, type.isFloatTokenType)
    }

    @ParameterizedTest
    @MethodSource("isValidIdentifierSource")
    fun testIsValidIdentifier(value: String, expectedResult: Boolean) {
        assertEquals(expectedResult, value.isValidIdentifier())
    }

    companion object {
        @JvmStatic
        fun matchTokenTypeSource(): Stream<Arguments> = twoArgumentsOf(
            "\'" to TokenTypes.ERROR,
            "# This is a comment" to TokenTypes.COMMENT,
            "(" to TokenTypes.LEFT_PAREN,
            ")" to TokenTypes.RIGHT_PAREN,
            ":" to TokenTypes.COLON,
            "+" to TokenTypes.PLUS,
            "-" to TokenTypes.MINUS,
            "%something" to TokenTypes.MACRO_PARAMETER,
            *listOf(
                listOf("zero", "at", "k0", "k1", "gp", "sp", "fp", "ra"),
                (0..3).map { "a$it" },
                (0..9).map { "t$it" },
                (0..7).map { "s$it" }
            ).flatten()
                .map { "\$$it" to TokenTypes.REGISTER_NAME }
                .toTypedArray(),
            *(0..31)
                .map { "\$$it" to TokenTypes.REGISTER_NUMBER }
                .toTypedArray(),
            *(0..31)
                .map { "\$f$it" to TokenTypes.FP_REGISTER_NAME }
                .toTypedArray(),
            *(0..31)
                .map { "$it" to TokenTypes.INTEGER_5 }
                .toTypedArray(),
            32768.toString() to TokenTypes.INTEGER_16U,
            (-16384).toString() to TokenTypes.INTEGER_16,
            (-1).toString() to TokenTypes.INTEGER_16,
            (Int.MAX_VALUE / 2).toString() to TokenTypes.INTEGER_32,
            (Float.MAX_VALUE / 4).toString() to TokenTypes.REAL_NUMBER,
            *Globals.instructionSet.instructionList
                .map { it.name to TokenTypes.OPERATOR }
                .toTypedArray(),
            *Directives.getDirectiveList()
                .map { it.name to TokenTypes.DIRECTIVE }
                .toTypedArray(),
            "\"this is a quoted string\\n\"" to TokenTypes.QUOTED_STRING,
            "main" to TokenTypes.IDENTIFIER,
            // Make sure that invalid tokens return an error
            "!((&)(*&!@(#)(!)(*!@#" to TokenTypes.ERROR,
            // Make sure that a dot for an unknown directive becomes an identifier
            ".random" to TokenTypes.IDENTIFIER
        )

        @JvmStatic
        fun isIntegerTokenTypeSource(): Stream<Arguments> = twoArgumentsOf(
            TokenTypes.COMMENT to false,
            TokenTypes.DIRECTIVE to false,
            TokenTypes.OPERATOR to false,
            TokenTypes.DELIMITER to false,
            TokenTypes.REGISTER_NAME to false,
            TokenTypes.REGISTER_NUMBER to false,
            TokenTypes.FP_REGISTER_NAME to false,
            TokenTypes.IDENTIFIER to false,
            TokenTypes.LEFT_PAREN to false,
            TokenTypes.RIGHT_PAREN to false,
            TokenTypes.INTEGER_5 to true,
            TokenTypes.INTEGER_16 to true,
            TokenTypes.INTEGER_16U to true,
            TokenTypes.INTEGER_32 to true,
            TokenTypes.REAL_NUMBER to false,
            TokenTypes.QUOTED_STRING to false,
            TokenTypes.PLUS to false,
            TokenTypes.MINUS to false,
            TokenTypes.COLON to false,
            TokenTypes.ERROR to false,
            TokenTypes.MACRO_PARAMETER to false
        )

        @JvmStatic
        fun isFloatingTokenTypeSource(): Stream<Arguments> = twoArgumentsOf(
            TokenTypes.COMMENT to false,
            TokenTypes.DIRECTIVE to false,
            TokenTypes.OPERATOR to false,
            TokenTypes.DELIMITER to false,
            TokenTypes.REGISTER_NAME to false,
            TokenTypes.REGISTER_NUMBER to false,
            TokenTypes.FP_REGISTER_NAME to false,
            TokenTypes.IDENTIFIER to false,
            TokenTypes.LEFT_PAREN to false,
            TokenTypes.RIGHT_PAREN to false,
            TokenTypes.INTEGER_5 to false,
            TokenTypes.INTEGER_16 to false,
            TokenTypes.INTEGER_16U to false,
            TokenTypes.INTEGER_32 to false,
            TokenTypes.REAL_NUMBER to true,
            TokenTypes.QUOTED_STRING to false,
            TokenTypes.PLUS to false,
            TokenTypes.MINUS to false,
            TokenTypes.COLON to false,
            TokenTypes.ERROR to false,
            TokenTypes.MACRO_PARAMETER to false
        )

        @JvmStatic
        fun isValidIdentifierSource(): Stream<Arguments> = twoArgumentsOf(
            "validIdentifier" to true,
            "_valid_identifier" to true,
            "\$valid\$Identifier" to true,
            "valid.identifier" to true,
            "1invalid" to false,
            "123invalid" to false,
            "*invalid" to false,
            "!name" to false,
            "invalid@name" to false,
            "name#123" to false,
            "abc-xyz" to false,
            "hello/world" to false,
            "my Identifier" to false,
            "abc def" to false,
            "" to false
        )
    }
}