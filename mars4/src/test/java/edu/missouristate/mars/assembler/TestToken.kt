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

import edu.missouristate.mars.MIPSProgram
import edu.missouristate.mars.oneArgumentOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

// The same brittle test warning applies here...

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestToken {
    private val testToken = Token(TokenTypes.ERROR, "", MIPSProgram(), 0, 0)

    @ParameterizedTest
    @MethodSource("setTypeSource")
    fun testSetType(newType: TokenTypes) {
        testToken.type = newType
        assertEquals(newType, testToken.type)
    }

    @ParameterizedTest
    @MethodSource("setOriginalProgramSource")
    fun testSetOriginalProgram(program: MIPSProgram?) {
        testToken.setOriginal(program, 0)
        assertEquals(program, testToken.originalProgram)
    }

    companion object {
        @JvmStatic
        fun setTypeSource(): Stream<Arguments> = oneArgumentOf(
            TokenTypes.COMMENT, TokenTypes.DIRECTIVE, TokenTypes.OPERATOR, TokenTypes.DELIMITER,
            TokenTypes.REGISTER_NAME, TokenTypes.FP_REGISTER_NAME, TokenTypes.IDENTIFIER, TokenTypes.LEFT_PAREN,
            TokenTypes.RIGHT_PAREN, TokenTypes.INTEGER_5, TokenTypes.INTEGER_16, TokenTypes.INTEGER_16U,
            TokenTypes.INTEGER_32, TokenTypes.REAL_NUMBER, TokenTypes.QUOTED_STRING, TokenTypes.PLUS,
            TokenTypes.MINUS, TokenTypes.COLON, TokenTypes.MACRO_PARAMETER
        )

        @JvmStatic
        fun setOriginalProgramSource(): Stream<Arguments> = oneArgumentOf(
            MIPSProgram(), null
        )
    }
}