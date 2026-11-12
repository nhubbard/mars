package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException
import edu.missouristate.mars.simulator.Exceptions.SYSCALL_EXCEPTION

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/** Writes the double to $f0 and $f1 and the dialog status to $a1. */
open class SyscallInputDialogDouble : AbstractSyscall(53, "InputDialogDouble") {
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement?, context: InstructionContext) {
        val message = context.readSyscallString(statement, context.registerFile.getValue(4))
        val input = context.environment().showInputDialog(message)
        try {
            context.setRegisterPairToDouble(0, 0.0)
            val status = when {
                input == null -> -2
                input.isEmpty() -> -3
                else -> {
                    context.setRegisterPairToDouble(0, input.toDouble())
                    0
                }
            }
            context.updateRegister(5, status)
        } catch (failure: InvalidRegisterAccessException) {
            context.updateRegister(5, -1)
            throw ProcessingException(
                context, statement, "invalid int reg. access during double input (syscall $number)", SYSCALL_EXCEPTION,
            )
        } catch (failure: NumberFormatException) {
            context.updateRegister(5, -1)
        }
    }
}
