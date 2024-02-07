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

package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.Coprocessor1.setRegisterPairToDouble
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.simulator.Exceptions
import java.util.*

/**
 * Service to return a random floating point value.
 */
class SyscallRandDouble : AbstractSyscall(44, "RandDouble") {
    /**
     * System call to get a double from the random number generator.
     * Return in $f0 the next pseudorandom, uniformly distributed double value between 0.0 and 1.0
     * from this random number generator's sequence.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments: $a0 = index of pseudorandom number generator
        // Return: $f0 = the next pseudorandom, uniformly distributed double value between 0.0 and 1.0
        // from this random number generator's sequence.
        val index = getValue(4)
        var stream = RandomStreams.randomStreams[index]
        if (stream == null) {
            stream = Random() // create a non-seeded stream
            RandomStreams.randomStreams[index] = stream
        }
        try {
            setRegisterPairToDouble(0, stream.nextDouble())
        } catch (e: InvalidRegisterAccessException) {   // register ID error in this method
            throw ProcessingException(
                statement,
                "Internal error storing double to register (syscall $number)",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
    }
}