package edu.missouristate.mars.api;

/**
 * Carries information about a single completed instruction step.
 *
 * @param pc the program counter value of the instruction that just executed
 * @param instruction the 32-bit machine-code word that executed
 * @param stepCount total number of instructions executed so far in this run
 * @param transfer observed call or exception transfer, or null for ordinary execution
 */
public record StepEvent(int pc, int instruction, long stepCount, Transfer transfer) {
  public StepEvent(int pc, int instruction, long stepCount) {
    this(pc, instruction, stepCount, null);
  }

  public sealed interface Transfer permits Call, ExceptionEntry, ExceptionReturn {}

  /** An executed jump or taken branch wrote its return address into a nonzero register. */
  public record Call(int linkRegister, int returnAddress) implements Transfer {}

  /** A recoverable fault or interrupt redirected execution to the exception handler. */
  public enum ExceptionEntry implements Transfer {
    INSTANCE
  }

  /** ERET restored execution from the exception program counter. */
  public enum ExceptionReturn implements Transfer {
    INSTANCE
  }
}
