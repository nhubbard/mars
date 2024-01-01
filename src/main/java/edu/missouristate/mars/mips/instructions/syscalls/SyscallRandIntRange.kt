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

package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import java.util.*

/**
 * Service to return a random integer in a specified range.
 */
class SyscallRandIntRange : AbstractSyscall(42, "RandIntRange") {
    /**
     * System call to the random number generator, with an upper range specified.
     * Return in $a0 the next pseudorandom, uniformly distributed int value between 0 (inclusive)
     * and the specified value (exclusive), drawn from this random number generator's sequence.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments:
        //    $a0 = index of pseudorandom number generator
        //    $a1 = the upper bound for the range of returned values.
        // Return: $a0 = the next pseudorandom, uniformly distributed int value from this
        // random number generator's sequence.
        val index = getValue(4)
        var stream = RandomStreams.randomStreams[index]
        if (stream == null) {
            stream = Random() // create a non-seeded stream
            RandomStreams.randomStreams[index] = stream
        }
        try {
            updateRegister(4, stream.nextInt(getValue(5)))
        } catch (iae: IllegalArgumentException) {
            throw ProcessingException(
                statement,
                "Upper bound of range cannot be negative (syscall $number)",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
    }
}
