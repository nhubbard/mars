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
import edu.missouristate.mars.mips.hardware.Coprocessor1.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.toRawLongBits
import edu.missouristate.mars.util.Binary.highOrderLongToInt
import edu.missouristate.mars.util.Binary.lowOrderLongToInt
import edu.missouristate.mars.util.SystemIO

/**
 * Service to read the bits of console input double into $f0 and $f1.
 * $f1 contains the high-order word of the double.
 */
class SyscallReadDouble : AbstractSyscall(7, "ReadDouble") {
    /**
     * Performs syscall function to read the bits of input double into $f0 and $f1.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Higher-numbered register contains high-order word so order is $f1 - $f0.
        val doubleValue: Double = try {
            SystemIO.readDouble(this.number)
        } catch (e: NumberFormatException) {
            throw ProcessingException(
                statement,
                "invalid double input (syscall " + this.number + ")",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
        val longValue = doubleValue.toRawLongBits()
        updateRegister(1, highOrderLongToInt(longValue))
        updateRegister(0, lowOrderLongToInt(longValue))
    }
}