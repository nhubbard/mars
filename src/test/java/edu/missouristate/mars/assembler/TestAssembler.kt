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
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.createProgram
import edu.missouristate.mars.tapSystemOut
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestAssembler {
    @Test
    fun testAssembleHelperFunctions() {
        val assembler = Assembler()
        val inputFile = Paths.get("src/test/resources/tests/macro_test.s").toFile()
        Globals.initialize(false)
        val program = MIPSProgram()
        program.prepareFilesForAssembly(arrayListOf(inputFile.absolutePath), inputFile.absolutePath, "")
        program.tokenize()
        assembler.assemble(program, true)
        assembler.assemble(program, true, false)
        assembler.assemble(arrayListOf(program), true)
        assembler.assemble(arrayListOf(program), true, false)
    }

    @Test
    fun testAssembleUnlikelyCase() {
        val assembler = Assembler()
        val input: ArrayList<MIPSProgram>? = null
        assertNull(assembler.assemble(input, true, false))
        val input2 = arrayListOf<MIPSProgram>()
        assertNull(assembler.assemble(input2, true, false))
    }

    @Test
    fun testAssembleDebugPrintOutput() {
        Globals.initialize(false)
        Globals.debug = true
        val output = tapSystemOut {
            createProgram("src/test/resources/tests/macro_test.s")
        }
        Globals.debug = false
        assertTrue(output.contains("Assembler first pass begins:"))
    }

    @Test
    fun testAssembleErrorLimitExceeded() {
        val (_, errors) = createProgram(
            "src/test/resources/tests/assembler_error_limit.s",
            ignoreErrors = true
        )
        assertTrue(errors.errorCount() > 200)
    }

    @Test
    fun testAssembleFirstFirstExceedsErrorLimit() {
        val (program, errors) = createProgram(
            "src/test/resources/tests/assembler_error_limit.s",
            "src/test/resources/tests/macro_test.s",
            ignoreErrors = true
        )
        assertTrue(errors.errorCount() > 200)
        assertTrue(program.localMacroPool.macrosUnderTesting.isEmpty())
    }

    @Test
    fun testAssembleErrorLimitExceededThrows() {
        assertThrows<ProcessingException> {
            createProgram("src/test/resources/tests/assembler_error_limit.s")
        }
    }

    @Test
    fun testAssembleErrorLimitExceededDoesNotThrowIfIgnoringErrors() {
        assertDoesNotThrow {
            createProgram("src/test/resources/tests/assembler_error_limit.s", ignoreErrors = true)
        }
    }

    @Test
    fun testAssembleInvalidMacroDef() {
        val (_, errors) = createProgram(
            "src/test/resources/tests/assembler_bad_macro_definition.s",
            ignoreErrors = true
        )
        assertTrue(errors.errorsOccurred())
    }
}