package edu.missouristate.mars.api;

/**
 * Read/write access to the MIPS integer register file (32 general-purpose registers plus PC, HI,
 * LO). Implementations must be thread-safe for reads from UI threads concurrent with simulation.
 */
public interface MarsRegisterFile {

  /**
   * Returns the current value of the given register.
   *
   * @param register register number in range 0–31; or 32 for PC, 33 for HI, 34 for LO
   * @return the 32-bit value
   * @throws IllegalArgumentException if {@code register} is out of range
   */
  int getValue(int register);

  /**
   * Sets the value of the given register.
   *
   * @param register register number in range 1–31 (register 0 is always zero and cannot be set);
   *     or 32 for PC, 33 for HI, 34 for LO
   * @param value the 32-bit value to write
   * @throws IllegalArgumentException if {@code register} is out of range
   */
  void setValue(int register, int value);

  /**
   * Returns the current program counter value.
   *
   * @return the PC
   */
  int getPC();

  /**
   * Resets all registers to their initial values (zeros, except $sp, $gp, and PC which are
   * restored to their configured initial values).
   */
  void reset();
}
