package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.legacy.LegacyApplication;
import java.util.function.Consumer;

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
 * Process-global compatibility facade for legacy interrupt and exception register callers.
 *
 * @deprecated Use the Coprocessor0State belonging to the simulation's MarsContext. Backstep
 *     recording remains on this facade until the execution pipeline is migrated.
 */
@Deprecated
public class Coprocessor0 {
  public static final int VADDR = Coprocessor0State.VADDR;
  public static final int STATUS = Coprocessor0State.STATUS;
  public static final int CAUSE = Coprocessor0State.CAUSE;
  public static final int EPC = Coprocessor0State.EPC;
  public static final int EXCEPTION_LEVEL = Coprocessor0State.EXCEPTION_LEVEL;
  public static final int DEFAULT_STATUS_VALUE = Coprocessor0State.DEFAULT_STATUS_VALUE;

  /** Transitional access for the legacy instruction execution context. */
  public static Coprocessor0State legacyState() {
    return LegacyApplication.services().context().coprocessor0;
  }

  public static void addRegistersObserver(Consumer<RegisterAccessNotice> listener) {
    legacyState().addRegistersObserver(listener);
  }

  public static void deleteRegistersObserver(Consumer<RegisterAccessNotice> listener) {
    legacyState().deleteRegistersObserver(listener);
  }

  public static void showRegisters() {
    legacyState().showRegisters();
  }

  public static int updateRegister(String n, int val) {
    return legacyState().updateRegister(n, val);
  }

  public static int updateRegister(int num, int val) {
    if (num != VADDR && num != STATUS && num != CAUSE && num != EPC) {
      return 0;
    }
    int old = legacyState().updateRegister(num, val);
    if (LegacyApplication.services().getProgram() != null
        && LegacyApplication.services().getSettings().getBackSteppingEnabled()) {
      LegacyApplication.services().getProgram().getBackStepper().addCoprocessor0Restore(num, old);
    }
    return old;
  }

  public static int getValue(int num) {
    return legacyState().getValue(num);
  }

  public static int getNumber(String n) {
    return legacyState().getNumber(n);
  }

  public static Register[] getRegisters() {
    return legacyState().getRegisters();
  }

  public static int getRegisterPosition(Register r) {
    return legacyState().getRegisterPosition(r);
  }

  public static Register getRegister(String rname) {
    return legacyState().getRegister(rname);
  }

  public static void resetRegisters() {
    legacyState().resetRegisters();
  }
}
