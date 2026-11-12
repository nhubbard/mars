package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.AddressErrorException

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Reads a console line with MARS fgets-style padding. */
open class SyscallReadString : AbstractSyscall(8, "ReadString") {
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement?, context: InstructionContext) {
        val address = context.registerFile.getValue(4)
        val requestedLength = context.registerFile.getValue(5) - 1
        val maxLength = requestedLength.coerceAtLeast(0)
        val input = context.environment().readString(maxLength).orEmpty()
        var length = minOf(maxLength, input.length)
        try {
            for (index in 0 until length) {
                context.setMemoryByte(address + index, input[index].code)
            }
            if (length < maxLength) {
                context.setMemoryByte(address + length, '\n'.code)
                length++
            }
            if (requestedLength >= 0) {
                context.setMemoryByte(address + length, 0)
            }
        } catch (failure: AddressErrorException) {
            throw ProcessingException(context, statement, failure)
        }
    }
}
