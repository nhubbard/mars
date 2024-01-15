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
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import javax.swing.JOptionPane
import kotlin.math.min

/**
 * Service to input data.
 */
class SyscallInputDialogString : AbstractSyscall(54, "InputDialogString") {
    /**
     * System call to input data.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments:
        //    $a0 = address of null-terminated string that is the message to user
        //    $a1 = address of input buffer for the input string
        //    $a2 = maximum number of characters to read
        // Outputs:
        //    $a1 contains status value
        //       0: valid input data, correctly parsed
        //       -1: input data cannot be correctly parsed
        //       -2: Cancel was chosen
        //       -3: OK was chosen but no data had been input into field
        var message = ""
        var byteAddress = getValue(4)
        val ch = charArrayOf(' ')
        try {
            ch[0] = Globals.memory.getByte(byteAddress).toChar()
            while (ch[0].code != 0) {
                message += String(ch)
                byteAddress++
                ch[0] = Globals.memory.getByte(byteAddress).toChar()
            }
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }

        // Values returned by Java's InputDialog:
        // A null return value means that "Cancel" was chosen rather than OK.
        // An empty string returned (that is, inputString.length() of zero)
        // means that OK was chosen but no string was input.
        val inputString = JOptionPane.showInputDialog(message)
        byteAddress = getValue(5) // byteAddress of string is in $a1
        val maxLength = getValue(6) // input buffer size for input string is in $a2

        try {
            if (inputString == null) {
                // Cancel was chosen
                updateRegister(5, -2) // set $a1 to -2 flag
            } else if (inputString.isEmpty()) {
                // OK was chosen but there was no input
                updateRegister(5, -3) // set $a1 to -3 flag
            } else {
                // The buffer will contain characters, a '\n' character, and the null character.
                // Copy the input data to the buffer as space permits.
                var index = 0
                while ((index < inputString.length) && (index < maxLength - 1)) {
                    Globals.memory.setByte(byteAddress + index, inputString[index].code)
                    index++
                }
                if (inputString.length < maxLength - 1) {
                    Globals.memory.setByte(
                        (byteAddress + min(inputString.length.toDouble(),
                        (maxLength - 2).toDouble())).toInt(),
                        '\n'.code
                    ) // newLine at string end
                }
                Globals.memory.setByte(
                    (byteAddress + min((inputString.length + 1).toDouble(), (maxLength - 1).toDouble())).toInt(),
                    0
                ) // null char to end string

                if (inputString.length > maxLength - 1) {
                    //  length of the input string exceeded the specified maximum
                    updateRegister(5, -4) // set $a1 to -4 flag
                } else {
                    updateRegister(5, 0) // set $a1 to 0 flag
                }
            }
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
    }
}
