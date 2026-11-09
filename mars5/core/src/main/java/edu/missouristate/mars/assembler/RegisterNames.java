package edu.missouristate.mars.assembler;

import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.Coprocessor1State;
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile;
import edu.missouristate.mars.mips.hardware.Register;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

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

  /** Canonical symbolic and decimal spellings for the general-purpose registers. */
  public static List<String> integerSpellings() {
    return Arrays.stream(INTEGER.getRegisters())
        .flatMap(register -> Stream.of(register.getName(), "$" + register.getNumber()))
        .toList();
  }

  /** Canonical assembler spellings for the floating-point registers. */
  public static List<String> floatingSpellings() {
    return Arrays.stream(FLOAT.getRegisters()).map(Register::getName).toList();
  }

  /** Numeric assembler spellings for implemented Coprocessor 0 registers. */
  public static List<String> coprocessor0Spellings() {
    return Arrays.stream(CP0.getRegisters()).map(register -> "$" + register.getNumber()).toList();
  }
}
