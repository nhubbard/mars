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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMacroPool {
    @Test
    fun testMacroExpansionLoop() {
        val (program, _) = createProgram("src/test/resources/tests/macropool_loop_test.s")
        val loopToken = Token(TokenTypes.IDENTIFIER, "intentional_loop", program, 5, 1)
        program.getLocalMacroPool().pushOnCallStack(loopToken)
        assertTrue(program.getLocalMacroPool().pushOnCallStack(Token(TokenTypes.OPERATOR, "intentional_loop", program, 5, 1)))
    }

    @Test
    fun testExpansionHistory() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        val firstToken = Token(TokenTypes.REGISTER_NAME, "\$t0", program, 2, 0)
        val secondToken = Token(TokenTypes.IDENTIFIER, "main", program, 3, 0)
        program.getLocalMacroPool().pushOnCallStack(firstToken)
        program.getLocalMacroPool().pushOnCallStack(secondToken)
        assertTrue(program.getLocalMacroPool().getExpansionHistory().contains("->"))
    }

    @Test
    fun testMatchesAnyMacroWithNoMacro() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test_no_macro.s")
        // Doesn't matter what I input to matchesAnyMacroName, should always return false since there are no macros
        assertFalse(program.getLocalMacroPool().matchesAnyMacroName("no_macro"))
    }

    @Test
    fun testMatchesAnyMacroWithCorrectName() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        assertTrue(program.getLocalMacroPool().matchesAnyMacroName("print_int"))
    }

    @Test
    fun testMatchesAnyMacroWithIncorrectName() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        assertFalse(program.getLocalMacroPool().matchesAnyMacroName("with_label"))
    }

    @Test
    fun testGetMatchingMacroWithEmptyTokenList() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        assertNull(program.getLocalMacroPool().getMatchingMacro(TokenList()))
    }

    @Test
    fun testGetMatchingMacroArgsSmallerThanTokenList() {
        val (program, _) = createProgram("src/test/resources/tests/macropool_args_less_than_token_list.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "my_macro", program, 0, 0))
        tokens.add(Token(TokenTypes.INTEGER_5, "10", program, 0, 9))
        assertNotNull(program.getLocalMacroPool().getMatchingMacro(tokens))
    }

    @Test
    fun testGetMatchingMacroWhenRetNull() {
        val (program, _) = createProgram("src/test/resources/tests/macropool_full_match.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "full_match", program, 7, 0))
        val expectedMacro = program.getLocalMacroPool().macroList.first()
        assertEquals(expectedMacro, program.getLocalMacroPool().getMatchingMacro(tokens))
    }

    @Test
    fun testGetMatchingMacroWhenRetNotNull() {
        val (program, _) = createProgram("src/test/resources/tests/macropool_full_match_2.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "full_match", program, 13, 0))
        val expectedMacro = program.getLocalMacroPool().macroList[1]
        assertEquals(expectedMacro, program.getLocalMacroPool().getMatchingMacro(tokens))
    }

    @Test
    fun testGetMatchingMacroNoConditionsMet() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test_no_macro.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "full_match", program, 4, 0))
        assertNull(program.getLocalMacroPool().getMatchingMacro(tokens))
    }

    @Test
    fun testGetMatchingMacroFirstConditionNotMet() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "print_ints", program, 9, 0))
        assertNull(program.getLocalMacroPool().getMatchingMacro(tokens))
    }

    @Test
    fun testGetMatchingMacroSecondConditionNotMet() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "print_int", program, 9, 0))
        assertNull(program.getLocalMacroPool().getMatchingMacro(tokens))
    }

    @Test
    fun testGetMatchingMacroThirdConditionNotMet() {
        val (program, _) = createProgram("src/test/resources/tests/macro_test.s")
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "print_int", program, 9, 0))
        tokens.add(Token(TokenTypes.INTEGER_5, "1", program, 9, 11))
        assertNotNull(program.getLocalMacroPool().getMatchingMacro(tokens))
    }

    @Test
    fun testGetMatchingMacroReturnsNullWhenPassedEmptyTokenList() {
        val macroPool = MacroPool(MIPSProgram())
        val tokens = TokenList()
        val result = macroPool.getMatchingMacro(tokens)
        assertNull(result)
    }

    @Test
    fun testGetMatchingMacroReturnsMacroWithMatchingNameAndArgumentCountWhenExists() {
        val macroPool = MacroPool(MIPSProgram())
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "macro1", null, 0, 0))

        val macro1 = Macro()
        macro1.name = "macro1"
        macro1.args = arrayListOf()
        macroPool.macroList.add(macro1)

        val result = macroPool.getMatchingMacro(tokens)
        assertEquals(macro1, result)
    }

    @Test
    fun testGetMatchingMacroReturnsMacroWithEarliestFromLineWithMultipleMacrosHaveSameNameAndArgumentCount() {
        val macroPool = MacroPool(MIPSProgram())
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "macro1", null, 0, 0))

        val macro1 = Macro()
        macro1.name = "macro1"
        macro1.args = arrayListOf()
        macro1.fromLine = 1
        macroPool.macroList.add(macro1)

        val macro2 = Macro()
        macro2.name = "macro1"
        macro2.args = arrayListOf()
        macro2.fromLine = 2
        macroPool.macroList.add(macro2)

        val result = macroPool.getMatchingMacro(tokens)

        assertEquals(macro1, result)
    }

    @Test
    fun testGetMatchingMacroReturnsNullWhenNoMacroWithMatchingNameAndArgumentCountExists() {
        val macroPool = MacroPool(MIPSProgram())
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "macro1", null, 0, 0))
        val result = macroPool.getMatchingMacro(tokens)
        assertNull(result)
    }

    @Test
    fun testGetMatchingMacroReturnsNullWhenNumberOfArgumentsDoesNotMatch() {
        val macroPool = MacroPool(MIPSProgram())
        val tokens = TokenList()
        tokens.add(Token(TokenTypes.IDENTIFIER, "macro1", null, 0, 0))
        tokens.add(Token(TokenTypes.IDENTIFIER, "arg1", null, 0, 0))

        val macro1 = Macro()
        macro1.name = "macro1"
        macro1.args = arrayListOf()
        macroPool.macroList.add(macro1)

        val result = macroPool.getMatchingMacro(tokens)

        assertNull(result)
    }
}