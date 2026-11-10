package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.*;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.util.*;

/*
Copyright (c) 2003-2007,  Pete Sanderson and Kenneth Vollmar

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

/** Service to read the wall-clock time into $a0 and $a1. */
public class SyscallTime extends AbstractSyscall {
  /** Builds the Time syscall (30). */
  public SyscallTime() {
    super(30, "Time");
  }

  /**
   * Performs syscall function to place current system time into $a0 (low order 32 bits) and $a1
   * (high order 32 bits).
   */
  public void simulate(ProgramStatement statement, InstructionContext context) {
    long value = context.environment().currentTimeMillis();
    context.updateRegister(4, Binary.lowOrderLongToInt(value)); // $a0
    context.updateRegister(5, Binary.highOrderLongToInt(value)); // $a1
  }
}
