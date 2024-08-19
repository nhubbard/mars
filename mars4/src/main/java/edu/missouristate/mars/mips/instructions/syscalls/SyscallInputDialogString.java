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

package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.RegisterFile;

import javax.swing.*;

/**
 * Service to input data.
 */

public class SyscallInputDialogString extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallInputDialogString() {
        super(54, "InputDialogString");
    }

    /**
     * System call to input data.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
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


        String message = ""; // = "";
        int byteAddress = RegisterFile.getValue(4); // byteAddress of string is in $a0
        char[] ch = {' '}; // Need an array to convert to String
        try {
            ch[0] = (char) Globals.memory.getByte(byteAddress);
            while (ch[0] != 0) // only uses single location ch[0]
            {
                message = message.concat(new String(ch)); // parameter to String constructor is a char[] array
                byteAddress++;
                ch[0] = (char) Globals.memory.getByte(byteAddress);
            }
        } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }

        // Values returned by Java's InputDialog:
        // A null return value means that "Cancel" was chosen rather than OK.
        // An empty string returned (that is, inputString.length() of zero)
        // means that OK was chosen but no string was input.
        String inputString;
        inputString = JOptionPane.showInputDialog(message);
        byteAddress = RegisterFile.getValue(5); // byteAddress of string is in $a1
        int maxLength = RegisterFile.getValue(6); // input buffer size for input string is in $a2

        try {
            if (inputString == null)  // Cancel was chosen
            {
                RegisterFile.updateRegister(5, -2);  // set $a1 to -2 flag
            } else if (inputString.isEmpty())  // OK was chosen but there was no input
            {
                RegisterFile.updateRegister(5, -3);  // set $a1 to -3 flag
            } else {
                // The buffer will contain characters, a '\n' character, and the null character
                // Copy the input data to buffer as space permits
                for (int index = 0; (index < inputString.length()) && (index < maxLength - 1); index++) {
                    Globals.memory.setByte(byteAddress + index,
                            inputString.charAt(index));
                }
                if (inputString.length() < maxLength - 1) {
                    Globals.memory.setByte(byteAddress + Math.min(inputString.length(), maxLength - 2), '\n');  // newline at string end
                }
                Globals.memory.setByte(byteAddress + Math.min((inputString.length() + 1), maxLength - 1), 0);  // null char to end string

                if (inputString.length() > maxLength - 1) {
                    //  length of the input string exceeded the specified maximum
                    RegisterFile.updateRegister(5, -4);  // set $a1 to -4 flag
                } else {
                    RegisterFile.updateRegister(5, 0);  // set $a1 to 0 flag
                }
            } // end else

        } // end try
        catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }


    }

}
