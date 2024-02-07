import edu.missouristate.mars.Globals
import edu.missouristate.mars.MIPSProgram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.assembler.SourceLine
import edu.missouristate.mars.mips.hardware.RegisterFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail

/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FibonacciIntegrationTest {
    companion object {
        private val sourceCode = """
# Compute first twelve Fibonacci numbers and put in array, then print
      .data
fibs: .word   0 : 12        # "array" of 12 words to contain fib values
size: .word  12             # size of "array"
      .text
      la   ${"$"}t0, fibs        # load address of array
      la   ${"$"}t5, size        # load address of size variable
      lw   ${"$"}t5, 0(${"$"}t5)      # load array size
      li   ${"$"}t2, 1           # 1 is first and second Fib. number
      add.d ${"$"}f0, ${"$"}f2, ${"$"}f4
      sw   ${"$"}t2, 0(${"$"}t0)      # F[0] = 1
      sw   ${"$"}t2, 4(${"$"}t0)      # F[1] = F[0] = 1
      addi ${"$"}t1, ${"$"}t5, -2     # Counter for loop, will execute (size-2) times
loop: lw   ${"$"}t3, 0(${"$"}t0)      # Get value from array F[n]
      lw   ${"$"}t4, 4(${"$"}t0)      # Get value from array F[n+1]
      add  ${"$"}t2, ${"$"}t3, ${"$"}t4    # ${"$"}t2 = F[n] + F[n+1]
      sw   ${"$"}t2, 8(${"$"}t0)      # Store F[n+2] = F[n] + F[n+1] in array
      addi ${"$"}t0, ${"$"}t0, 4      # increment address of Fib. number source
      addi ${"$"}t1, ${"$"}t1, -1     # decrement loop counter
      bgtz ${"$"}t1, loop        # repeat if not finished yet.
      la   ${"$"}a0, fibs        # first argument for print (array)
      add  ${"$"}a1, ${"$"}zero, ${"$"}t5  # second argument for print (size)
      jal  print            # call print routine.
      li   ${"$"}v0, 10          # system call for exit
      syscall               # we are out of here.

#########  routine to print the numbers on one line.

      .data
space:.asciiz  " "          # space to insert between numbers
head: .asciiz  "The Fibonacci numbers are:\\r"
      .text   
print:add  ${"$"}t0, ${"$"}zero, ${"$"}a0  # starting address of array
      add  ${"$"}t1, ${"$"}zero, ${"$"}a1  # initialize loop counter to array size
      la   ${"$"}a0, head        # load address of print heading
      li   ${"$"}v0, 4           # specify Print String service
      syscall               # print heading
out:  lw   ${"$"}a0, 0(${"$"}t0)      # load fibonacci number for syscall
      li   ${"$"}v0, 1           # specify Print Integer service
      syscall               # print fibonacci number
      la   ${"$"}a0, space       # load address of spacer for syscall
      li   ${"$"}v0, 4           # specify Print String service
      syscall               # output string
      addi ${"$"}t0, ${"$"}t0, 4      # increment address
      addi ${"$"}t1, ${"$"}t1, -1     # decrement loop counter
      bgtz ${"$"}t1, out         # repeat if not finished
      jr   ${"$"}ra              # return
        """.trimIndent()
    }

    @Test
    fun testFibonacci() {
        Globals.initialize()
        RegisterFile.resetRegisters()
        RegisterFile.initializeProgramCounter(false)
        Globals.program.setSourceLineList(arrayListOf(
            *sourceCode.lines().mapIndexed { index, it ->
                SourceLine(it, Globals.program, index)
            }.toTypedArray()
        ))
        try {
            Globals.program.tokenize()
            val warnings = Globals.program.assemble(arrayListOf(Globals.program), true)
            if (warnings.hasWarnings) println(warnings.generateErrorAndWarningReport())
        } catch (e: ProcessingException) {
            fail { e.localizedMessage ?: "ProcessingException thrown, but there is no failure message." }
        }
    }
}