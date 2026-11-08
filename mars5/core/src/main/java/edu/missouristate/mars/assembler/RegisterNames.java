package edu.missouristate.mars.assembler;

import edu.missouristate.mars.mips.hardware.*;

/** Register-name metadata; banks are private and are never mutated or exposed. */
public final class RegisterNames {
  private static final IntegerRegisterFile INTEGER = new IntegerRegisterFile();
  private static final Coprocessor1State FLOAT = new Coprocessor1State();
  private static final Coprocessor0State CP0 = new Coprocessor0State();

  public static Register coprocessor0(String name) {
    return CP0.getRegister(name);
  }

  private RegisterNames() {}

  public static Register integer(String name) {
    return INTEGER.getUserRegister(name);
  }

  public static Register floating(String name) {
    return FLOAT.getRegister(name);
  }
}
