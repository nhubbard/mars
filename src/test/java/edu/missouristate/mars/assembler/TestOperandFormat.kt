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

import edu.missouristate.mars.*
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestOperandFormat {
    @BeforeAll
    fun initialize() {
        Globals.initialize(false)
        Globals.program = MIPSProgram()
    }

    @ParameterizedTest
    @MethodSource("tokenOperandMatchSource")
    fun testTokenOperandMatch(candidateList: TokenList, inst: Instruction) {
        assertTrue(OperandFormat.tokenOperandMatch(candidateList, inst, ErrorList()))
    }

    @ParameterizedTest
    @MethodSource("badTokenOperandMatchSource")
    fun testBadTokenOperandMatch(candidateList: TokenList, inst: Instruction) {
        val errors = ErrorList()
        assertFalse(OperandFormat.tokenOperandMatch(candidateList, inst, errors))
    }

    @ParameterizedTest
    @MethodSource("bestOperandMatchSource")
    fun testBestOperandMatch(tokenList: TokenList, matches: ArrayList<Instruction>?, expectedOutput: Instruction?) {
        assertEquals(expectedOutput, OperandFormat.bestOperandMatch(tokenList, matches))
    }

    @Test
    fun testIdentifierOperatorTypeCheck() {
        val specTokenList = TokenList()
        specTokenList.add(Token(TokenTypes.IDENTIFIER, "j", MIPSProgram(), 0, 0))
        specTokenList.add(Token(TokenTypes.IDENTIFIER, "specValue", MIPSProgram(), 0, 2))
        val specInstruction = mock(BasicInstruction::class.java)
        `when`(specInstruction.tokenList).thenReturn(specTokenList)

        val candidateTokenList = TokenList()
        candidateTokenList.add(Token(TokenTypes.IDENTIFIER, "j", MIPSProgram(), 0, 0))
        candidateTokenList.add(Token(TokenTypes.OPERATOR, "specValue", MIPSProgram(), 0, 2))
        val errorList = ErrorList()
        assertTrue(OperandFormat.operandTypeCheck(candidateTokenList, specInstruction, errorList))
    }

    @Test
    fun testRegisterNameWithTypeCheck() {
        val originalValue = Globals.getSettings().getBooleanSetting(Settings.BARE_MACHINE_ENABLED)
        Globals.getSettings().setBooleanSettingNonPersistent(Settings.BARE_MACHINE_ENABLED, true)

        val specTokenList = TokenList()
        specTokenList.add(Token(TokenTypes.IDENTIFIER, "jr", MIPSProgram(), 0, 0))
        specTokenList.add(Token(TokenTypes.REGISTER_NAME, "\$t0", MIPSProgram(), 0, 3))
        val specInstruction = mock(BasicInstruction::class.java)
        `when`(specInstruction.tokenList).thenReturn(specTokenList)

        val candidateTokenList = TokenList()
        candidateTokenList.add(Token(TokenTypes.IDENTIFIER, "jr", MIPSProgram(), 0, 0))
        candidateTokenList.add(Token(TokenTypes.REGISTER_NAME, "\$t0", MIPSProgram(), 0, 3))

        val errorList = ErrorList()

        assertFalse(OperandFormat.operandTypeCheck(candidateTokenList, specInstruction, errorList))
        assertTrue(errorList.errorsOccurred())
        assertEquals(1, errorList.errorCount())

        Globals.getSettings().setBooleanSettingNonPersistent(Settings.BARE_MACHINE_ENABLED, originalValue)
    }

    @ParameterizedTest
    @MethodSource("differentIntegerTypesSource")
    fun testMismatchedIntegerTypes(p: IntegerTypesParams, expectedOutput: Boolean) {
        val specTokenList = TokenList()
        specTokenList.add(Token(TokenTypes.IDENTIFIER, p.instrName, MIPSProgram(), 0, 0))
        specTokenList.add(Token(p.expectedType, p.expectedValue, MIPSProgram(), 0, p.instrName.length + p.expectedValue.length + 1))
        val specInstruction = mock(BasicInstruction::class.java)
        `when`(specInstruction.tokenList).thenReturn(specTokenList)

        val candidateTokenList = TokenList()
        candidateTokenList.add(Token(TokenTypes.IDENTIFIER, p.instrName, MIPSProgram(), 0, 0))
        candidateTokenList.add(Token(p.candidateType, p.candidateValue, MIPSProgram(), 0, p.instrName.length + p.candidateValue.length + 1))

        val errorList = ErrorList()
        val result = OperandFormat.operandTypeCheck(candidateTokenList, specInstruction, errorList)
        if (expectedOutput) {
            assertTrue(result)
        } else {
            assertFalse(result)
            assertTrue(errorList.errorsOccurred())
            assertEquals(1, errorList.errorCount())
        }
    }

    @AfterAll
    fun resetGlobals() {
        Globals.program = null
        Globals.resetInitialized()
    }

    companion object {
        @JvmStatic
        fun tokenOperandMatchSource(): Stream<Arguments> = twoArgumentsOf(
            // Successful match for every instruction
            *Globals.instructionSet.instructionList.map {
                it.tokenList to it
            }.toTypedArray()
        )

        @JvmStatic
        fun badTokenOperandMatchSource(): Stream<Arguments> =
            Globals.instructionSet.instructionList.let { il ->
                twoArgumentsOf(
                    // Bad number of operands for `addi`, which takes 3 arguments
                    TokenList().apply {
                        add(Token(TokenTypes.REGISTER_NAME, "\$t0", Globals.program, 1, 5))
                        add(Token(TokenTypes.DELIMITER, ",", Globals.program, 1, 8))
                        add(Token(TokenTypes.REGISTER_NAME, "\$t0", Globals.program, 1, 9))
                    } to il[il.indexOfFirst { it.exampleFormat.startsWith("addi") }],
                    // Incorrect operands for `addi`, which takes a register and an immediate value
                    TokenList().apply {
                        add(Token(TokenTypes.INTEGER_5, "1", Globals.program, 1, 6))
                        add(Token(TokenTypes.DELIMITER, ",", Globals.program, 1, 7))
                        add(Token(TokenTypes.INTEGER_5, "2", Globals.program, 1, 8))
                        add(Token(TokenTypes.DELIMITER, ",", Globals.program, 1, 9))
                        add(Token(TokenTypes.INTEGER_5, "3", Globals.program, 1, 10))
                    } to il[il.indexOfFirst { it.exampleFormat.startsWith("addi") }]
                )
            }

        @JvmStatic
        fun bestOperandMatchSource(): Stream<Arguments> =
            Globals.instructionSet.instructionList.let { il ->
                threeArgumentsOf(
                    (TokenList() to null) tri null,
                    (TokenList() to arrayListOf(il[0])) tri il[0],
                    (il[1].tokenList to arrayListOf(*il.subList(0, 5).toTypedArray())) tri il[1],
                    (il[0].tokenList to arrayListOf(*il.subList(2, 5).toTypedArray())) tri il[0]
                )
            }

        @JvmStatic
        fun differentIntegerTypesSource(): Stream<Arguments> = twoArgumentsOf(
            IntegerTypesParams("ani", TokenTypes.INTEGER_16, "1000", TokenTypes.INTEGER_5, "5") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16U, "1000", TokenTypes.INTEGER_5, "5") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_32, "1000", TokenTypes.INTEGER_5, "5") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_32, "1000", TokenTypes.INTEGER_16U, "500") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_32, "1000", TokenTypes.INTEGER_16, "500") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16, "32767", TokenTypes.INTEGER_16U, "32767") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16U, "32768", TokenTypes.INTEGER_16, "32768") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16, "32767", TokenTypes.INTEGER_16, "32767") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16U, "32768", TokenTypes.INTEGER_16U, "32768") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_5, "1000", TokenTypes.INTEGER_16, "1000") to false,
            IntegerTypesParams("ani", TokenTypes.INTEGER_5, "1000", TokenTypes.INTEGER_16U, "1000") to false,
            IntegerTypesParams("ani", TokenTypes.INTEGER_5, "1000", TokenTypes.INTEGER_32, "1000") to false,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16, "1000", TokenTypes.INTEGER_16U, "1000") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16U, "1000", TokenTypes.INTEGER_16, "1000") to true,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16U, "1000", TokenTypes.INTEGER_32, "1000") to false,
            IntegerTypesParams("ani", TokenTypes.INTEGER_16, "1000", TokenTypes.INTEGER_32, "1000") to false
        )

        data class IntegerTypesParams(
            val instrName: String,
            val expectedType: TokenTypes,
            val expectedValue: String,
            val candidateType: TokenTypes,
            val candidateValue: String
        )
    }
}