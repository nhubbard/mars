package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.*;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;

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

/** Service to read the bits of input float into $f0 */
public class SyscallReadFloat extends AbstractSyscall {
  /**
   * Build an instance of the Read Float syscall. Default service number is 6 and name is
   * "ReadFloat".
   */
  public SyscallReadFloat() {
    super(6, "ReadFloat");
  }

  /** Performs syscall function to read the bits of input float into $f0 */
  public void simulate(ProgramStatement statement, InstructionContext context)
      throws ProcessingException {
    float floatValue;
    try {
      floatValue = context.environment(env).readFloat();
    } catch (NumberFormatException e) {
      throw new ProcessingException(
          context,
          statement,
          "invalid float input (syscall " + this.getNumber() + ")",
          Exceptions.SYSCALL_EXCEPTION);
    }
    context.updateCoprocessor1(0, Float.floatToRawIntBits(floatValue));
  }
}
