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

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.Globals
import edu.missouristate.mars.MIPSProgram
import edu.missouristate.mars.ProcessingException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMacroPool {
    private fun createProgram(path: String): Pair<MIPSProgram, ErrorList> {
        val inputFile = Paths.get(path).toFile()
        Globals.initialize(false)
        val program = MIPSProgram()
        program.prepareFilesForAssembly(arrayListOf(inputFile.absolutePath), inputFile.absolutePath, "")
        program.tokenize()
        val errors = program.assemble(arrayListOf(program), true, false)
        program.simulate(-1)
        return program to errors
    }

    @Test
    fun testMacroExpansionLoop() {
        val (program, _) = createProgram("src/test/resources/tests/macropool_loop_test.s")
        val loopToken = Token(TokenTypes.IDENTIFIER, "intentional_loop", program, 5, 1)
        program.localMacroPool.pushOnCallStack(loopToken)
        assertTrue(program.localMacroPool.pushOnCallStack(Token(TokenTypes.OPERATOR, "intentional_loop", program, 5, 1)))
    }

    @Test
    @Disabled
    fun testExpansionHistory() {
        val (program, _) = createProgram("src/test/resources/tests/macropool_nested_macros.s")
        assertTrue(program.localMacroPool.expansionHistory.contains("->"))
    }

    @Test
    fun testMatchesAnyMacroWithNoMacro() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test_no_macro.s")
        // Doesn't matter what I input to matchesAnyMacroName, should always return false since there are no macros
        assertFalse(program.localMacroPool.matchesAnyMacroName("no_macro"))
    }

    @Test
    fun testMatchesAnyMacroWithCorrectName() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        assertTrue(program.localMacroPool.matchesAnyMacroName("print_int"))
    }

    @Test
    fun testMatchesAnyMacroWithIncorrectName() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        assertFalse(program.localMacroPool.matchesAnyMacroName("with_label"))
    }

    @Test
    fun testGetMatchingMacroWithEmptyTokenList() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        assertNull(program.localMacroPool.getMatchingMacro(TokenList(), 0))
    }

    @Test
    fun testGetMatchingMacroArgsSmallerThanTokenList() {
        val (program, _) = createProgram("src/test/resources/tests/macropool_args_less_than_token_list.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "my_macro", program, 0, 0))
        tokens.add(Token(TokenTypes.INTEGER_5, "10", program, 0, 9))
        assertNotNull(program.localMacroPool.getMatchingMacro(tokens, 0))
    }
}