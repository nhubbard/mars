package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.*;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.mips.hardware.*;

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

/** Service to input data. */
public class SyscallInputDialogInt extends AbstractSyscall {
  /** Build an instance of the syscall with its default service number and name. */
  public SyscallInputDialogInt() {
    super(51, "InputDialogInt");
  }

  /** System call to input data. */
  public void simulate(ProgramStatement statement, InstructionContext context)
      throws ProcessingException {
    // Input arguments: $a0 = address of null-terminated string that is the message to user
    // Outputs:
    //    $a0 contains value of int read
    //    $a1 contains status value
    //       0: valid input data, correctly parsed
    //       -1: input data cannot be correctly parsed
    //       -2: Cancel was chosen
    //       -3: OK was chosen but no data had been input into field

    String message = ""; // = "";
    int byteAddress = context.registerFile.getValue(4);
    char[] ch = {' '}; // Need an array to convert to String
    try {
      ch[0] = (char) Globals.memory.getByte(byteAddress);
      while (ch[0] != 0) // only uses single location ch[0]
      {
        message =
            message.concat(new String(ch)); // parameter to String constructor is a char[] array
        byteAddress++;
        ch[0] = (char) Globals.memory.getByte(byteAddress);
      }
    } catch (AddressErrorException e) {
      throw new ProcessingException(context, statement, e);
    }

    // Values returned by Java's InputDialog:
    // A null return value means that "Cancel" was chosen rather than OK.
    // An empty string returned (that is, inputValue.length() of zero)
    // means that OK was chosen but no string was input.
    String inputValue;
    inputValue = context.environment(env).showInputDialog(message);
    if (inputValue == null) // Cancel was chosen
    {
      context.updateRegister(4, 0); // set $a0 to zero
      context.updateRegister(5, -2); // set $a1 to -2 flag
    } else if (inputValue.isEmpty()) // OK was chosen but there was no input
    {
      context.updateRegister(4, 0); // set $a0 to zero
      context.updateRegister(5, -3); // set $a1 to -3 flag
    } else {
      try {
        int i = Integer.parseInt(inputValue);

        // Successful parse of valid input data
        context.updateRegister(4, i); // set $a0 to the data read
        context.updateRegister(5, 0); // set $a1 to valid flag
      } catch (NumberFormatException e) {
        // Unsuccessful parse of input data
        context.updateRegister(4, 0); // set $a0 to zero
        context.updateRegister(5, -1); // set $a1 to -1 flag
      }
    } // end else
  }
}
