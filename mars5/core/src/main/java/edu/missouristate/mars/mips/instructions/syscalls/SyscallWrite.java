package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.*;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.mips.hardware.*;

/*
Copyright (c) 2003-2009,  Pete Sanderson and Kenneth Vollmar

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

/**
 * Service to write to file descriptor given in $a0. $a1 specifies buffer and $a2 specifies length.
 * Number of characters written is returned in $v0 (this was changed from $a0 in MARS 3.7 for SPIM
 * compatibility. The table in COD erroneously shows $a0).
 */
public class SyscallWrite extends AbstractSyscall {
  /**
   * Build an instance of the Write file syscall. Default service number is 15 and name is "Write".
   */
  public SyscallWrite() {
    super(15, "Write");
  }

  /**
   * Performs syscall function to write to file descriptor given in $a0. $a1 specifies buffer and
   * $a2 specifies length. Number of characters written is returned in $v0, starting in MARS 3.7.
   */
  public void simulate(ProgramStatement statement, InstructionContext context)
      throws ProcessingException {
    int byteAddress = context.registerFile.getValue(5); // source of characters to write to file
    byte b;
    int reqLength = context.registerFile.getValue(6); // user-requested length
    int index = 0;
    byte[] myBuffer =
        new byte[context.registerFile.getValue(6) + 1]; // specified length plus null termination
    try {
      b = (byte) context.memory().getByte(byteAddress);
      while (index < reqLength) // Stop at requested length. Null bytes are included.
      // while (index < reqLength && b != 0) // Stop at requested length OR null byte
      {
        myBuffer[index++] = b;
        byteAddress++;
        b = (byte) context.memory().getByte(byteAddress);
      }

      myBuffer[index] = 0; // Add string termination
    } // end try
    catch (AddressErrorException e) {
      throw new ProcessingException(context, statement, e);
    }
    int retValue =
        context
            .environment()
            .writeToFile(
                context.registerFile.getValue(4), // fd
                myBuffer, // buffer
                context.registerFile.getValue(6)); // length
    context.updateRegister(2, retValue); // set returned value in register
  }
}
