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

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.SystemIO
import kotlin.math.min

/**
 * Service to read console input string into buffer starting at address in $a0.
 */
class SyscallReadString : AbstractSyscall(8, "ReadString") {
    /**
     * Performs syscall function to read console input string into buffer starting at address in $a0.
     * Follows semantics of UNIX 'fgets'.
     * For specified length n, string can be no longer than n-1.
     * If less than that, add newline to end.
     * In either case, then pad with null byte.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        val inputString: String
        val buf = getValue(4) // buf addr in $a0
        var maxLength = getValue(5) - 1 // $a1
        var addNullByte = true
        // Guard against negative maxLength.  DPS 13-July-2011
        if (maxLength < 0) {
            maxLength = 0
            addNullByte = false
        }
        inputString = SystemIO.readString(this.number, maxLength)
        var stringLength = min(maxLength.toDouble(), inputString.length.toDouble()).toInt()
        try {
            for (index in 0 until stringLength) {
                Globals.memory.setByte(buf + index, inputString[index].code)
            }
            if (stringLength < maxLength) {
                Globals.memory.setByte(buf + stringLength, '\n'.code)
                stringLength++
            }
            if (addNullByte) Globals.memory.setByte(buf + stringLength, 0)
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
    }
}