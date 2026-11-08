package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.api.EventEmitter;
import edu.missouristate.mars.api.MarsRegisterFile;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.util.Binary;
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
 * Owns the integer registers of one simulation, including PC, HI, LO, and access listeners. This
 * class does not consult process-wide state or record backsteps; the deprecated RegisterFile facade
 * retains those legacy responsibilities until execution is migrated.
 *
 * @author Jason Bumgarner, Jason Shrewsbury
 * @version June 2003
 */
public final class IntegerRegisterFile implements MarsRegisterFile {

  public static final int GLOBAL_POINTER_REGISTER = 28;
  public static final int STACK_POINTER_REGISTER = 29;

  private final EventEmitter<RegisterAccessNotice> registerEvents = new EventEmitter<>();

  public void addRegistersObserver(Consumer<RegisterAccessNotice> listener) {
    registerEvents.subscribe(listener);
  }

  public void deleteRegistersObserver(Consumer<RegisterAccessNotice> listener) {
    registerEvents.unsubscribe(listener);
  }

  private final Register[] regFile;

  private static Register[] createRegisters(int globalPointer, int stackPointer) {
    return new Register[] {
      new Register("$zero", 0, 0),
      new Register("$at", 1, 0),
      new Register("$v0", 2, 0),
      new Register("$v1", 3, 0),
      new Register("$a0", 4, 0),
      new Register("$a1", 5, 0),
      new Register("$a2", 6, 0),
      new Register("$a3", 7, 0),
      new Register("$t0", 8, 0),
      new Register("$t1", 9, 0),
      new Register("$t2", 10, 0),
      new Register("$t3", 11, 0),
      new Register("$t4", 12, 0),
      new Register("$t5", 13, 0),
      new Register("$t6", 14, 0),
      new Register("$t7", 15, 0),
      new Register("$s0", 16, 0),
      new Register("$s1", 17, 0),
      new Register("$s2", 18, 0),
      new Register("$s3", 19, 0),
      new Register("$s4", 20, 0),
      new Register("$s5", 21, 0),
      new Register("$s6", 22, 0),
      new Register("$s7", 23, 0),
      new Register("$t8", 24, 0),
      new Register("$t9", 25, 0),
      new Register("$k0", 26, 0),
      new Register("$k1", 27, 0),
      new Register("$gp", GLOBAL_POINTER_REGISTER, globalPointer),
      new Register("$sp", STACK_POINTER_REGISTER, stackPointer),
      new Register("$fp", 30, 0),
      new Register("$ra", 31, 0)
    };
  }

  private final Register programCounter;
  private final Register hi =
      new Register("hi", 33, 0); // this is an internal register with arbitrary number
  private final Register lo =
      new Register("lo", 34, 0); // this is an internal register with arbitrary number

  /** Creates registers using the standard MARS reset addresses without initializing a runtime. */
  public IntegerRegisterFile() {
    this(
        MemoryConfigurations.getDefaultGlobalPointer(),
        MemoryConfigurations.getDefaultStackPointer(),
        MemoryConfigurations.getDefaultTextBaseAddress());
  }

  /** Creates registers with reset addresses belonging to this simulation. */
  public IntegerRegisterFile(int globalPointer, int stackPointer, int textBaseAddress) {
    regFile = createRegisters(globalPointer, stackPointer);
    programCounter = new Register("pc", 32, textBaseAddress);
    for (Register r : regFile) {
      r.addObserver(registerEvents::emit);
    }
    hi.addObserver(registerEvents::emit);
    lo.addObserver(registerEvents::emit);
  }

  /** Method for displaying the register values for debugging. */
  public void showRegisters() {
    for (Register register : regFile) {
      System.out.println("Name: " + register.getName());
      System.out.println("Number: " + register.getNumber());
      System.out.println("Value: " + register.getValue());
      System.out.println();
    }
  }

  /**
   * This method updates the register value who's number is num. Also handles the lo and hi
   * registers
   *
   * @param num Register to set the value of.
   * @param val The desired value for the register.
   */
  public int updateRegister(int num, int val) {
    checkRegisterNumber(num);
    if (num == 0) {
      return 0;
    }
    if (num == 32) {
      return setProgramCounter(val);
    }
    return register(num).setValue(val);
  }

  /**
   * Sets the value of the register given to the value given.
   *
   * @param reg Name of register to set the value of.
   * @param val The desired value for the register.
   */
  public void updateRegister(String reg, int val) {
    if (!reg.equals("zero")) {
      for (int i = 0; i < regFile.length; i++) {
        if (regFile[i].getName().equals(reg)) {
          updateRegister(i, val);
          break;
        }
      }
    } else {
      System.out.println("You can not change the value of the zero register.");
    }
  }

  /**
   * Returns the value of the register who's number is num.
   *
   * @param num The register number.
   * @return The value of the given register.
   */
  public int getValue(int num) {
    checkRegisterNumber(num);
    return register(num).getValue();
  }

  /**
   * For getting the number representation of the register.
   *
   * @param n The string formatted register name to look for.
   * @return The number of the register represented by the string or -1 if no match.
   */
  public int getNumber(String n) {
    int j = -1;
    for (Register register : regFile) {
      if (register.getName().equals(n)) {
        j = register.getNumber();
        break;
      }
    }
    return j;
  }

  /**
   * For returning the set of registers.
   *
   * @return The set of registers.
   */
  public Register[] getRegisters() {
    return regFile;
  }

  /**
   * Get register object corresponding to given name. If no match, return null.
   *
   * @param Rname The register name, either in $0 or $zero format.
   * @return The register object,or null if not found.
   */
  public Register getUserRegister(String Rname) {
    Register reg = null;
    if (Rname.charAt(0) == '$') {
      try {
        // check for register number 0-31.
        reg = regFile[Binary.stringToInt(Rname.substring(1))]; // KENV 1/6/05
      } catch (Exception e) {
        // handles both NumberFormat and ArrayIndexOutOfBounds
        // check for register mnemonic $zero thru $ra
        reg = null; // just to be sure
        // just do linear search; there aren't that many registers
        for (Register register : regFile) {
          if (Rname.equals(register.getName())) {
            reg = register;
            break;
          }
        }
      }
    }
    return reg;
  }

  /**
   * Initializes the program counter without consulting global symbols or recording undo history.
   * Backstep recording is the responsibility of the caller.
   *
   * @param value The value to set the Program Counter to.
   */
  public void initializeProgramCounter(int value) {
    programCounter.setValue(value);
  }

  /**
   * For setting the Program Counter. Note that ordinary PC update should be done using
   * incrementPC() method. Use this only when processing jumps and branches.
   *
   * @param value The value to set the Program Counter to.
   * @return previous PC value
   */
  public int setProgramCounter(int value) {
    return programCounter.setValue(value);
  }

  /**
   * For returning the program counters value.
   *
   * @return The program counters value as an int.
   */
  public int getProgramCounter() {
    return programCounter.getValue();
  }

  /**
   * Returns Register object for program counter. Use with caution.
   *
   * @return program counter's Register object.
   */
  public Register getProgramCounterRegister() {
    return programCounter;
  }

  /**
   * For returning the program counter's initial (reset) value.
   *
   * @return The program counter's initial value
   */
  public int getInitialProgramCounter() {
    return programCounter.getResetValue();
  }

  /** Resets every register to this instance's configured initial value. */
  public void resetRegisters() {
    for (Register register : regFile) {
      register.resetValue();
    }
    programCounter.resetValue();
    hi.resetValue();
    lo.resetValue();
  }

  /** Method to increment the Program counter in the general case (not a jump or branch). */
  public void incrementPC() {
    programCounter.setValue(programCounter.getValue() + Instruction.INSTRUCTION_LENGTH);
  }

  @Override
  public void setValue(int register, int value) {
    updateRegister(register, value);
  }

  @Override
  public int getPC() {
    return getProgramCounter();
  }

  @Override
  public void reset() {
    resetRegisters();
  }

  private static void checkRegisterNumber(int number) {
    if (number < 0 || number > 34) {
      throw new IllegalArgumentException("Invalid register number: " + number);
    }
  }

  private Register register(int number) {
    return switch (number) {
      case 32 -> programCounter;
      case 33 -> hi;
      case 34 -> lo;
      default -> regFile[number];
    };
  }
}
