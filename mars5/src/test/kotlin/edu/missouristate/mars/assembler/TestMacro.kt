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
import edu.missouristate.mars.createProgram
import edu.missouristate.mars.threeArgumentsOf
import edu.missouristate.mars.tri
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMacro {
    @Test
    fun testGettersAndSetters() {
        val macro = Macro()

        macro.name = "TestMacro"
        assertEquals("TestMacro", macro.name)

        val program = MIPSProgram()
        macro.program = program
        assertEquals(program, macro.program)

        macro.fromLine = 10
        assertEquals(10, macro.fromLine)

        macro.originalFromLine = 10
        assertEquals(10, macro.originalFromLine)

        macro.toLine = 10
        assertEquals(10, macro.toLine)

        macro.originalToLine = 10
        assertEquals(10, macro.originalToLine)

        macro.args = arrayListOf("hello", "world")
        assertTrue(macro.args.containsAll(listOf("hello", "world")))
    }

    @Test
    fun testEquals() {
        val macro1 = Macro()
        macro1.name = "Macro1"
        macro1.addArg("arg1")

        val macro2 = Macro()
        macro2.name = "Macro1"
        macro2.addArg("arg1")

        val macro3 = Macro()
        macro3.name = "Macro3"
        macro3.addArg("arg2")

        val macro4 = Macro()
        macro4.name = "Macro1"
        macro4.addArg("arg")
        macro4.addArg("arg2")

        assertTrue(macro1 == macro2)
        assertFalse(macro1 == macro3)
        assertFalse(macro1.equals(null))
        assertFalse(macro1 == Any())
        assertFalse(macro1 == macro4)
    }

    @Test
    fun testAddAndGetArgs() {
        val macro = Macro()
        macro.addArg("arg1")
        macro.addArg("arg2")
        assertEquals(arrayListOf("arg1", "arg2"), macro.args)
    }

    @ParameterizedTest
    @MethodSource("substitutedLineSource")
    fun testGetSubstitutedLine(fileName: String, macroName: String?, shouldHaveErrors: Boolean) {
        val (program, _) = createProgram("src/test/resources/tests/$fileName")
        macroName?.let {
            assertTrue(program.getLocalMacroPool().matchesAnyMacroName(it))
        } ?: assertNull(program.getLocalMacroPool().current)
    }

    @Test
    fun testLabelMethods() {
        val macro = Macro()
        macro.addLabel("label1")
        macro.addLabel("label3")
        macro.addLabel("label2")
        macro.readyForCommit()
        assertTrue(macro.tokenIsMacroLabel("label1"))
        assertTrue(macro.tokenIsMacroLabel("label2"))
        assertTrue(macro.tokenIsMacroLabel("label3"))
        assertFalse(macro.tokenIsMacroLabel("label4"))
    }

    @ParameterizedTest
    @MethodSource("tokenIsMacroParameterSource")
    fun testTokenIsMacroParameter(value: String, acceptSpim: Boolean, expectedResult: Boolean) {
        assertEquals(expectedResult, Macro.tokenIsMacroParameter(value, acceptSpim))
    }

    @Test
    fun testReplaceToken() {
        val existsToken = Token(TokenTypes.IDENTIFIER, "old", MIPSProgram(), 1, 0)
        assertEquals("new", Macro().replaceToken("old", existsToken, "new"))
        val notExistsToken = Token(TokenTypes.IDENTIFIER, "notReplaced", MIPSProgram(), 1, 0)
        assertEquals("thing", Macro().replaceToken("thing", notExistsToken, "another"))
    }

    companion object {
        @JvmStatic
        fun tokenIsMacroParameterSource(): Stream<Arguments> = threeArgumentsOf(
            ("%param" to false) tri true,
            ("\$param" to false) tri false,
            ("\$param" to true) tri true,
            ("\$vaddr" to true) tri false,
            ("\$f31" to true) tri false,
            ("param" to true) tri false,
            ("" to true) tri false
        )

        @JvmStatic
        fun substitutedLineSource(): Stream<Arguments> = threeArgumentsOf(
            ("macro_test.s" to "print_int") tri false,
            ("macro_test_no_args.s" to "print_one") tri false,
            ("macro_test_no_macro.s" to null) tri false,
            ("macro_test_two_args.s" to "two_args") tri false,
            ("macro_test_label_inside.s" to "with_label") tri false
        )
    }
}