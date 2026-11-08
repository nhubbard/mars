package edu.missouristate.mars.simulator;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.*;
import edu.missouristate.mars.util.*;

public final class LegacyExceptions {
  /**
   * Given MIPS exception cause code, will place that code into coprocessor 0 CAUSE register ($13),
   * set the EPC register to "current" program counter, and set Exception Level bit in STATUS
   * register.
   *
   * @param cause The cause code (see Exceptions for a list)
   */
  public static void setRegisters(int cause) {
    // Set CAUSE register bits 2 thru 6 to cause value.  The "& 0xFFFFFC83" will set bits 2-6 and
    // 8-9 to 0 while
    // keeping all the others.  Left-shift by 2 to put cause value into position then OR it in.
    // Bits 8-9 used to
    // identify devices for External Interrupt (8=keyboard,9=display).
    Coprocessor0.updateRegister(
        Coprocessor0.CAUSE,
        (Coprocessor0.getValue(Coprocessor0.CAUSE) & 0xFFFFFC83 | (cause << 2)));
    // When exception occurred, PC had already been incremented so need to subtract 4 here.
    Coprocessor0.updateRegister(
        Coprocessor0.EPC, RegisterFile.getProgramCounter() - Instruction.INSTRUCTION_LENGTH);
    // Set EXL (Exception Level) bit, bit position 1, in STATUS register to 1.
    Coprocessor0.updateRegister(
        Coprocessor0.STATUS,
        Binary.setBit(Coprocessor0.getValue(Coprocessor0.STATUS), Coprocessor0.EXCEPTION_LEVEL));
  }

  /**
   * Given MIPS exception cause code and bad address, place the bad address into VADDR register ($8)
   * then call overloaded setRegisters with the cause code to do the rest.
   *
   * @param cause The cause code (see Exceptions for a list). Should be address exception.
   * @param addr The address that caused the exception.
   */
  public static void setRegisters(int cause, int addr) {
    Coprocessor0.updateRegister(Coprocessor0.VADDR, addr);
    setRegisters(cause);
  }
}
