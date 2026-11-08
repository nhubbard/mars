package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.Globals;
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
 * Process-global compatibility facade for legacy floating-point register callers.
 *
 * @deprecated Use the Coprocessor1State belonging to the simulation's MarsContext. Backstep
 *     recording remains on this facade until the execution pipeline is migrated.
 */
@Deprecated
public class Coprocessor1 {

  /** Transitional access for the legacy instruction execution context. */
  public static Coprocessor1State legacyState() {
    return Globals.context().coprocessor1;
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

  public static void setRegisterToFloat(String reg, float val) {
    legacyState().setRegisterToFloat(reg, val);
  }

  public static void setRegisterToFloat(int reg, float val) {
    legacyState().setRegisterToFloat(reg, val);
  }

  public static void setRegisterToInt(String reg, int val) {
    legacyState().setRegisterToInt(reg, val);
  }

  public static void setRegisterToInt(int reg, int val) {
    legacyState().setRegisterToInt(reg, val);
  }

  public static void setRegisterPairToDouble(int reg, double val)
      throws InvalidRegisterAccessException {
    legacyState().setRegisterPairToDouble(reg, val);
  }

  public static void setRegisterPairToDouble(String reg, double val)
      throws InvalidRegisterAccessException {
    legacyState().setRegisterPairToDouble(reg, val);
  }

  public static void setRegisterPairToLong(int reg, long val)
      throws InvalidRegisterAccessException {
    legacyState().setRegisterPairToLong(reg, val);
  }

  public static void setRegisterPairToLong(String reg, long val)
      throws InvalidRegisterAccessException {
    legacyState().setRegisterPairToLong(reg, val);
  }

  public static float getFloatFromRegister(int reg) {
    return legacyState().getFloatFromRegister(reg);
  }

  public static float getFloatFromRegister(String reg) {
    return legacyState().getFloatFromRegister(reg);
  }

  public static int getIntFromRegister(int reg) {
    return legacyState().getIntFromRegister(reg);
  }

  public static int getIntFromRegister(String reg) {
    return legacyState().getIntFromRegister(reg);
  }

  public static double getDoubleFromRegisterPair(int reg) throws InvalidRegisterAccessException {
    return legacyState().getDoubleFromRegisterPair(reg);
  }

  public static double getDoubleFromRegisterPair(String reg) throws InvalidRegisterAccessException {
    return legacyState().getDoubleFromRegisterPair(reg);
  }

  public static long getLongFromRegisterPair(int reg) throws InvalidRegisterAccessException {
    return legacyState().getLongFromRegisterPair(reg);
  }

  public static long getLongFromRegisterPair(String reg) throws InvalidRegisterAccessException {
    return legacyState().getLongFromRegisterPair(reg);
  }

  public static int updateRegister(int num, int val) {
    if (num < 0 || num >= 32) return 0;
    int old = legacyState().updateRegister(num, val);
    if (Globals.program != null
        && Globals.program != null
        && Globals.getSettings().getBackSteppingEnabled()) {
      Globals.program.getBackStepper().addCoprocessor1Restore(num, old);
    }
    return old;
  }

  public static int getValue(int num) {
    return legacyState().getValue(num);
  }

  public static int getRegisterNumber(String n) {
    return legacyState().getRegisterNumber(n);
  }

  public static Register[] getRegisters() {
    return legacyState().getRegisters();
  }

  public static Register getRegister(String rName) {
    return legacyState().getRegister(rName);
  }

  public static void resetRegisters() {
    legacyState().resetRegisters();
  }

  public static int setConditionFlag(int flag) {
    int old = legacyState().setConditionFlag(flag);
    if (flag >= 0
        && flag < legacyState().getConditionFlagCount()
        && Globals.program != null
        && Globals.getSettings().getBackSteppingEnabled()) {
      if (old == 0) {
        Globals.program.getBackStepper().addConditionFlagClear(flag);
      } else {
        Globals.program.getBackStepper().addConditionFlagSet(flag);
      }
    }
    return old;
  }

  public static int clearConditionFlag(int flag) {
    int old = legacyState().clearConditionFlag(flag);
    if (flag >= 0
        && flag < legacyState().getConditionFlagCount()
        && Globals.program != null
        && Globals.getSettings().getBackSteppingEnabled()) {
      if (old == 0) {
        Globals.program.getBackStepper().addConditionFlagClear(flag);
      } else {
        Globals.program.getBackStepper().addConditionFlagSet(flag);
      }
    }
    return old;
  }

  public static int getConditionFlag(int flag) {
    return legacyState().getConditionFlag(flag);
  }

  public static int getConditionFlags() {
    return legacyState().getConditionFlags();
  }

  public static void clearConditionFlags() {
    legacyState().clearConditionFlags();
  }

  public static void setConditionFlags() {
    legacyState().setConditionFlags();
  }

  public static int getConditionFlagCount() {
    return legacyState().getConditionFlagCount();
  }
}
