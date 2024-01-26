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

import edu.missouristate.mars.threeArgumentsOf
import edu.missouristate.mars.twoArgumentsOf
import edu.missouristate.mars.tri
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDataTypes {
    // NOTE: Some of these tests will seem useless on the surface, but I did accidentally change some constants in the
    // converted codebase, so anything is fair game.

    @ParameterizedTest
    @MethodSource("constantValuesSource")
    fun testConstantValues(expectedSize: Any, value: Any) {
        assertEquals(expectedSize, value)
    }

    @ParameterizedTest
    @MethodSource("lengthInBytesSource")
    fun testGetLengthInBytes(directive: Directives, expectedSize: Int) {
        assertEquals(expectedSize, DataTypes.getLengthInBytes(directive))
    }

    @ParameterizedTest
    @MethodSource("intOutOfRangeSource")
    fun testIntOutOfRange(directive: Directives, value: Int, expectedResult: Boolean) {
        assertEquals(expectedResult, DataTypes.outOfRange(directive, value))
    }

    @ParameterizedTest
    @MethodSource("floatOutOfRangeSource")
    fun testFloatOutOfRange(directive: Directives, value: Double, expectedResult: Boolean) {
        assertEquals(expectedResult, DataTypes.outOfRange(directive, value))
    }

    companion object {
        @JvmStatic
        fun constantValuesSource(): Stream<Arguments> = twoArgumentsOf(
            8 to DataTypes.DOUBLE_SIZE,
            4 to DataTypes.FLOAT_SIZE,
            4 to DataTypes.WORD_SIZE,
            2 to DataTypes.HALF_SIZE,
            1 to DataTypes.BYTE_SIZE,
            1 to DataTypes.CHAR_SIZE,
            Integer.MAX_VALUE to DataTypes.MAX_WORD_VALUE,
            Integer.MIN_VALUE to DataTypes.MIN_WORD_VALUE,
            32767 to DataTypes.MAX_HALF_VALUE,
            -32768 to DataTypes.MIN_HALF_VALUE,
            65535 to DataTypes.MAX_UHALF_VALUE,
            0 to DataTypes.MIN_UHALF_VALUE,
            Byte.MAX_VALUE.toInt() to DataTypes.MAX_BYTE_VALUE,
            Byte.MIN_VALUE.toInt() to DataTypes.MIN_BYTE_VALUE,
            Float.MAX_VALUE.toDouble() to DataTypes.MAX_FLOAT_VALUE,
            -Float.MAX_VALUE.toDouble() to DataTypes.LOW_FLOAT_VALUE,
            Double.MAX_VALUE to DataTypes.MAX_DOUBLE_VALUE,
            -Double.MAX_VALUE to DataTypes.LOW_DOUBLE_VALUE
        )

        @JvmStatic
        fun lengthInBytesSource(): Stream<Arguments> = twoArgumentsOf(
            Directives.DATA to 0,
            Directives.TEXT to 0,
            Directives.WORD to DataTypes.WORD_SIZE,
            Directives.ASCII to 0,
            Directives.ASCIIZ to 0,
            Directives.BYTE to DataTypes.BYTE_SIZE,
            Directives.ALIGN to 0,
            Directives.HALF to DataTypes.HALF_SIZE,
            Directives.SPACE to 0,
            Directives.DOUBLE to DataTypes.DOUBLE_SIZE,
            Directives.FLOAT to DataTypes.FLOAT_SIZE,
            Directives.EXTERN to 0,
            Directives.KDATA to 0,
            Directives.KTEXT to 0,
            Directives.GLOBL to 0,
            Directives.SET to 0,
            Directives.EQV to 0,
            Directives.MACRO to 0,
            Directives.END_MACRO to 0,
            Directives.INCLUDE to 0
        )

        @JvmStatic
        fun intOutOfRangeSource(): Stream<Arguments> = threeArgumentsOf(
            (Directives.HALF to DataTypes.MIN_HALF_VALUE - 1) tri true,
            (Directives.HALF to DataTypes.MAX_HALF_VALUE + 1) tri true,
            (Directives.HALF to DataTypes.MIN_HALF_VALUE) tri false,
            (Directives.HALF to DataTypes.MAX_HALF_VALUE) tri false,
            (Directives.HALF to 0) tri false,
            (Directives.BYTE to DataTypes.MIN_BYTE_VALUE - 1) tri true,
            (Directives.BYTE to DataTypes.MAX_BYTE_VALUE + 1) tri true,
            (Directives.BYTE to DataTypes.MIN_BYTE_VALUE) tri false,
            (Directives.BYTE to DataTypes.MAX_BYTE_VALUE) tri false,
            (Directives.BYTE to 0) tri false
        )

        @JvmStatic
        fun floatOutOfRangeSource(): Stream<Arguments> = threeArgumentsOf(
            (Directives.FLOAT to Float.NEGATIVE_INFINITY) tri true,
            (Directives.FLOAT to DataTypes.LOW_FLOAT_VALUE) tri false,
            (Directives.FLOAT to Float.POSITIVE_INFINITY) tri true,
            (Directives.FLOAT to DataTypes.MAX_FLOAT_VALUE) tri false,
            (Directives.FLOAT to 1.0) tri false,
            (Directives.DOUBLE to 10.0) tri false
        )
    }
}