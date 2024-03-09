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

import edu.missouristate.mars.twoArgumentsOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDirectives {
    @ParameterizedTest
    @MethodSource("exactMatchSource")
    fun testMatchExact(value: String, expectedMatch: Directives?) {
        assertEquals(expectedMatch, Directives.matchDirective(value))
    }

    @ParameterizedTest
    @MethodSource("prefixMatchSource")
    fun testPrefixMatch(value: String, expectedMatches: ArrayList<Directives>?) {
        if (expectedMatches == null) {
            assertEquals(null, Directives.prefixMatchDirectives(value))
        } else assertTrue(Directives.prefixMatchDirectives(value)?.containsAll(expectedMatches) ?: false)
    }

    @ParameterizedTest
    @MethodSource("isIntegerDirectiveSource")
    fun testIsIntegerDirective(directive: Directives, expectedResult: Boolean) {
        assertEquals(expectedResult, Directives.isIntegerDirective(directive))
    }

    @ParameterizedTest
    @MethodSource("isFloatingDirectiveSource")
    fun testIsFloatingDirective(directive: Directives, expectedResult: Boolean) {
        assertEquals(expectedResult, Directives.isFloatingDirective(directive))
    }

    companion object {
        @JvmStatic
        fun exactMatchSource(): Stream<Arguments> = twoArgumentsOf(
            ".data" to Directives.DATA,
            ".text" to Directives.TEXT,
            ".word" to Directives.WORD,
            ".ascii" to Directives.ASCII,
            ".asciiz" to Directives.ASCIIZ,
            ".byte" to Directives.BYTE,
            ".align" to Directives.ALIGN,
            ".half" to Directives.HALF,
            ".space" to Directives.SPACE,
            ".double" to Directives.DOUBLE,
            ".float" to Directives.FLOAT,
            ".extern" to Directives.EXTERN,
            ".kdata" to Directives.KDATA,
            ".ktext" to Directives.KTEXT,
            ".globl" to Directives.GLOBL,
            ".set" to Directives.SET,
            ".eqv" to Directives.EQV,
            ".macro" to Directives.MACRO,
            ".end_macro" to Directives.END_MACRO,
            ".include" to Directives.INCLUDE,
            ".random" to null
        )

        @JvmStatic
        fun prefixMatchSource(): Stream<Arguments> = twoArgumentsOf(
            "." to Directives.getDirectiveList(),
            ".a" to arrayListOf(Directives.ALIGN, Directives.ASCII, Directives.ASCIIZ),
            ".b" to arrayListOf(Directives.BYTE),
            ".c" to null,
            ".d" to arrayListOf(Directives.DATA, Directives.DOUBLE),
            ".e" to arrayListOf(Directives.END_MACRO, Directives.EQV, Directives.EXTERN),
            ".f" to arrayListOf(Directives.FLOAT),
            ".g" to arrayListOf(Directives.GLOBL),
            ".h" to arrayListOf(Directives.HALF),
            ".i" to arrayListOf(Directives.INCLUDE),
            ".j" to null,
            ".k" to arrayListOf(Directives.KDATA, Directives.KTEXT),
            ".l" to null,
            ".m" to arrayListOf(Directives.MACRO),
            ".n" to null,
            ".o" to null,
            ".p" to null,
            ".q" to null,
            ".r" to null,
            ".s" to arrayListOf(Directives.SET, Directives.SPACE),
            ".t" to arrayListOf(Directives.TEXT),
            ".u" to null,
            ".v" to null,
            ".w" to arrayListOf(Directives.WORD),
            ".x" to null,
            ".y" to null,
            ".z" to null
        )

        @JvmStatic
        fun isIntegerDirectiveSource(): Stream<Arguments> = twoArgumentsOf(
            Directives.ALIGN to false,
            Directives.ASCII to false,
            Directives.ASCIIZ to false,
            Directives.BYTE to true,
            Directives.DATA to false,
            Directives.DOUBLE to false,
            Directives.END_MACRO to false,
            Directives.EQV to false,
            Directives.EXTERN to false,
            Directives.FLOAT to false,
            Directives.GLOBL to false,
            Directives.HALF to true,
            Directives.INCLUDE to false,
            Directives.KDATA to false,
            Directives.KTEXT to false,
            Directives.MACRO to false,
            Directives.SET to false,
            Directives.SPACE to false,
            Directives.TEXT to false,
            Directives.WORD to true
        )

        @JvmStatic
        fun isFloatingDirectiveSource(): Stream<Arguments> = twoArgumentsOf(
            Directives.ALIGN to false,
            Directives.ASCII to false,
            Directives.ASCIIZ to false,
            Directives.BYTE to false,
            Directives.DATA to false,
            Directives.DOUBLE to true,
            Directives.END_MACRO to false,
            Directives.EQV to false,
            Directives.EXTERN to false,
            Directives.FLOAT to true,
            Directives.GLOBL to false,
            Directives.HALF to false,
            Directives.INCLUDE to false,
            Directives.KDATA to false,
            Directives.KTEXT to false,
            Directives.MACRO to false,
            Directives.SET to false,
            Directives.SPACE to false,
            Directives.TEXT to false,
            Directives.WORD to false
        )
    }
}