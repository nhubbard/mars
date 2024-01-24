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
        fun constantValuesSource(): Stream<Arguments> = Stream.of(
            Arguments.of(8, DataTypes.DOUBLE_SIZE),
            Arguments.of(4, DataTypes.FLOAT_SIZE),
            Arguments.of(4, DataTypes.WORD_SIZE),
            Arguments.of(2, DataTypes.HALF_SIZE),
            Arguments.of(1, DataTypes.BYTE_SIZE),
            Arguments.of(1, DataTypes.CHAR_SIZE),
            Arguments.of(Integer.MAX_VALUE, DataTypes.MAX_WORD_VALUE),
            Arguments.of(Integer.MIN_VALUE, DataTypes.MIN_WORD_VALUE),
            Arguments.of(32767, DataTypes.MAX_HALF_VALUE),
            Arguments.of(-32768, DataTypes.MIN_HALF_VALUE),
            Arguments.of(65535, DataTypes.MAX_UHALF_VALUE),
            Arguments.of(0, DataTypes.MIN_UHALF_VALUE),
            Arguments.of(Byte.MAX_VALUE.toInt(), DataTypes.MAX_BYTE_VALUE),
            Arguments.of(Byte.MIN_VALUE.toInt(), DataTypes.MIN_BYTE_VALUE),
            Arguments.of(Float.MAX_VALUE.toDouble(), DataTypes.MAX_FLOAT_VALUE),
            Arguments.of(-Float.MAX_VALUE.toDouble(), DataTypes.LOW_FLOAT_VALUE),
            Arguments.of(Double.MAX_VALUE, DataTypes.MAX_DOUBLE_VALUE),
            Arguments.of(-Double.MAX_VALUE, DataTypes.LOW_DOUBLE_VALUE)
        )

        @JvmStatic
        fun lengthInBytesSource(): Stream<Arguments> = Stream.of(
            Arguments.of(Directives.DATA, 0),
            Arguments.of(Directives.TEXT, 0),
            Arguments.of(Directives.WORD, DataTypes.WORD_SIZE),
            Arguments.of(Directives.ASCII, 0),
            Arguments.of(Directives.ASCIIZ, 0),
            Arguments.of(Directives.BYTE, DataTypes.BYTE_SIZE),
            Arguments.of(Directives.ALIGN, 0),
            Arguments.of(Directives.HALF, DataTypes.HALF_SIZE),
            Arguments.of(Directives.SPACE, 0),
            Arguments.of(Directives.DOUBLE, DataTypes.DOUBLE_SIZE),
            Arguments.of(Directives.FLOAT, DataTypes.FLOAT_SIZE),
            Arguments.of(Directives.EXTERN, 0),
            Arguments.of(Directives.KDATA, 0),
            Arguments.of(Directives.KTEXT, 0),
            Arguments.of(Directives.GLOBL, 0),
            Arguments.of(Directives.SET, 0),
            Arguments.of(Directives.EQV, 0),
            Arguments.of(Directives.MACRO, 0),
            Arguments.of(Directives.END_MACRO, 0),
            Arguments.of(Directives.INCLUDE, 0)
        )

        @JvmStatic
        fun intOutOfRangeSource(): Stream<Arguments> = Stream.of(
            Arguments.of(Directives.HALF, DataTypes.MIN_HALF_VALUE - 1, true),
            Arguments.of(Directives.HALF, DataTypes.MAX_HALF_VALUE + 1, true),
            Arguments.of(Directives.HALF, DataTypes.MIN_HALF_VALUE, false),
            Arguments.of(Directives.HALF, DataTypes.MAX_HALF_VALUE, false),
            Arguments.of(Directives.HALF, 0, false),
            Arguments.of(Directives.BYTE, DataTypes.MIN_BYTE_VALUE - 1, true),
            Arguments.of(Directives.BYTE, DataTypes.MAX_BYTE_VALUE + 1, true),
            Arguments.of(Directives.BYTE, DataTypes.MIN_BYTE_VALUE, false),
            Arguments.of(Directives.BYTE, DataTypes.MAX_BYTE_VALUE, false),
            Arguments.of(Directives.BYTE, 0, false)
        )

        @JvmStatic
        fun floatOutOfRangeSource(): Stream<Arguments> = Stream.of(
            Arguments.of(Directives.FLOAT, Float.NEGATIVE_INFINITY, true),
            Arguments.of(Directives.FLOAT, DataTypes.LOW_FLOAT_VALUE, false),
            Arguments.of(Directives.FLOAT, Float.POSITIVE_INFINITY, true),
            Arguments.of(Directives.FLOAT, DataTypes.MAX_FLOAT_VALUE, false),
            Arguments.of(Directives.FLOAT, 1.0, false)
        )
    }
}