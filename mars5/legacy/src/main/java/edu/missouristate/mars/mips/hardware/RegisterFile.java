package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.legacy.LegacyApplication;
import java.util.function.Consumer;

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

/**
 * Process-global compatibility facade for legacy simulator and GUI callers.
 *
 * @deprecated Use the IntegerRegisterFile belonging to the simulation's MarsContext. Backstep
 *     recording remains on this facade until the execution pipeline is migrated.
 */
@Deprecated
public class RegisterFile {
  public static final int GLOBAL_POINTER_REGISTER = IntegerRegisterFile.GLOBAL_POINTER_REGISTER;
  public static final int STACK_POINTER_REGISTER = IntegerRegisterFile.STACK_POINTER_REGISTER;

  /** Transitional access for the legacy instruction execution context. */
  public static IntegerRegisterFile legacyState() {
    return LegacyApplication.services().context().registerFile;
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

  public static int updateRegister(int num, int val) {
    if (num == 0 || num == 32 || num < 0 || num > 34) {
      return 0;
    }
    int old = legacyState().updateRegister(num, val);
    if (LegacyApplication.services().getProgram() != null
        && LegacyApplication.services().getSettings().getBackSteppingEnabled()) {
      LegacyApplication.services().getProgram().getBackStepper().addRegisterFileRestore(num, old);
    }
    return old;
  }

  public static void updateRegister(String reg, int val) {
    int number = legacyState().getNumber(reg);
    if (number >= 0) {
      updateRegister(number, val);
    }
  }

  public static int getValue(int num) {
    // The historical API exposes PC through getProgramCounter(), not register 32.
    if (num < 0 || num == 32 || num > 34) {
      throw new ArrayIndexOutOfBoundsException(num);
    }
    return legacyState().getValue(num);
  }

  public static int getNumber(String n) {
    return legacyState().getNumber(n);
  }

  public static Register[] getRegisters() {
    return legacyState().getRegisters();
  }

  public static Register getUserRegister(String Rname) {
    return legacyState().getUserRegister(Rname);
  }

  public static void initializeProgramCounter(int value) {
    legacyState().initializeProgramCounter(value);
  }

  public static void initializeProgramCounter(boolean startAtMain) {
    int mainAddr = LegacyApplication.services().symbolTable.getAddress(SymbolTable.getStartLabel());
    if (startAtMain
        && mainAddr != SymbolTable.NOT_FOUND
        && (Memory.inTextSegment(mainAddr) || Memory.inKernelTextSegment(mainAddr))) {
      initializeProgramCounter(mainAddr);
    } else {
      initializeProgramCounter(legacyState().getInitialProgramCounter());
    }
  }

  public static int setProgramCounter(int value) {
    int old = legacyState().setProgramCounter(value);
    if (LegacyApplication.services().getProgram() != null
        && LegacyApplication.services().getSettings().getBackSteppingEnabled()) {
      LegacyApplication.services().getProgram().getBackStepper().addPCRestore(old);
    }
    return old;
  }

  public static int getProgramCounter() {
    return legacyState().getProgramCounter();
  }

  public static Register getProgramCounterRegister() {
    return legacyState().getProgramCounterRegister();
  }

  public static int getInitialProgramCounter() {
    return legacyState().getInitialProgramCounter();
  }

  public static void resetRegisters() {
    legacyState().resetRegisters();
    initializeProgramCounter(
        LegacyApplication.services()
            .getSettings()
            .getBooleanSetting(edu.missouristate.mars.Settings.START_AT_MAIN));
  }

  public static void incrementPC() {
    legacyState().incrementPC();
  }
}
