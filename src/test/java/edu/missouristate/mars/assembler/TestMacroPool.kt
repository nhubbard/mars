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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestMacroPool {
    @Test
    fun testMacroExpansionLoop() {
        val inputFile = Paths.get("src/test/resources/tests/macropool_loop_test.s").toFile()
        Globals.initialize(false)
        val program = MIPSProgram()
        program.prepareFilesForAssembly(arrayListOf(inputFile.absolutePath), inputFile.absolutePath, "")
        program.tokenize()
        program.assemble(arrayListOf(program), true, false)
        // program.simulate(-1)
        val loopToken = Token(TokenTypes.IDENTIFIER, "intentional_loop", program, 5, 1)
        program.localMacroPool.pushOnCallStack(loopToken)
        assertTrue(program.localMacroPool.pushOnCallStack(Token(TokenTypes.OPERATOR, "intentional_loop", program, 5, 1)))
    }

    @Test
    @Disabled
    fun testExpansionHistory() {
        val inputFile = Paths.get("src/test/resources/tests/macropool_nested_macros.s").toFile()
        Globals.initialize(false)
        val program = MIPSProgram()
        program.prepareFilesForAssembly(arrayListOf(inputFile.absolutePath), inputFile.absolutePath, "")
        program.tokenize()
        program.assemble(arrayListOf(program), true, false)
        program.simulate(-1)
        println(program.localMacroPool.expansionHistory)
        assertTrue(program.localMacroPool.expansionHistory.contains("->"))
    }
}