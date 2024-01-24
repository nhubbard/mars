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
import edu.missouristate.mars.MIPSProgram
import edu.missouristate.mars.argumentsOf
import edu.missouristate.mars.tri
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths
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

        assertTrue(macro1 == macro2)
        assertFalse(macro1 == macro3)
        assertFalse(macro1.equals(null))
        assertFalse(macro1 == Any())
    }

    @Test
    fun testAddAndGetArgs() {
        val macro = Macro()
        macro.addArg("arg1")
        macro.addArg("arg2")
        assertEquals(arrayListOf("arg1", "arg2"), macro.args)
    }

    @Test
    fun testGetSubstitutedLine() {
        val inputFile = Paths.get("src/test/resources/tests/macro_test.s").toFile()
        Globals.initialize(false)
        val program = MIPSProgram()
        program.prepareFilesForAssembly(arrayListOf(inputFile.absolutePath), inputFile.absolutePath, "")
        program.tokenize()
        program.assemble(arrayListOf(program), true, false)
        assertTrue(program.localMacroPool.matchesAnyMacroName("print_int"))
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

    companion object {
        @JvmStatic
        fun tokenIsMacroParameterSource(): Stream<Arguments> = argumentsOf(
            ("%param" to false) tri true,
            ("\$param" to false) tri false,
            ("\$param" to true) tri true,
            ("param" to true) tri false
        )
    }
}