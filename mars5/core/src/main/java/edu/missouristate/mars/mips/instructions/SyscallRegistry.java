package edu.missouristate.mars.mips.instructions;

import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.mips.instructions.syscalls.*;
import java.util.Map;

/**
 * Static registry of all MIPS syscall implementations, keyed by service number. Replaces the
 * reflection-based {@code SyscallLoader} that scanned the classpath at runtime.
 */
public final class SyscallRegistry {

  static final Map<Integer, Syscall> BY_NUMBER =
      Map.ofEntries(
          Map.entry(1, new SyscallPrintInt()),
          Map.entry(2, new SyscallPrintFloat()),
          Map.entry(3, new SyscallPrintDouble()),
          Map.entry(4, new SyscallPrintString()),
          Map.entry(5, new SyscallReadInt()),
          Map.entry(6, new SyscallReadFloat()),
          Map.entry(7, new SyscallReadDouble()),
          Map.entry(8, new SyscallReadString()),
          Map.entry(9, new SyscallSbrk()),
          Map.entry(10, new SyscallExit()),
          Map.entry(11, new SyscallPrintChar()),
          Map.entry(12, new SyscallReadChar()),
          Map.entry(13, new SyscallOpen()),
          Map.entry(14, new SyscallRead()),
          Map.entry(15, new SyscallWrite()),
          Map.entry(16, new SyscallClose()),
          Map.entry(17, new SyscallExit2()),
          Map.entry(30, new SyscallTime()),
          Map.entry(31, new SyscallMidiOut()),
          Map.entry(32, new SyscallSleep()),
          Map.entry(33, new SyscallMidiOutSync()),
          Map.entry(34, new SyscallPrintIntHex()),
          Map.entry(35, new SyscallPrintIntBinary()),
          Map.entry(36, new SyscallPrintIntUnsigned()),
          Map.entry(40, new SyscallRandSeed()),
          Map.entry(41, new SyscallRandInt()),
          Map.entry(42, new SyscallRandIntRange()),
          Map.entry(43, new SyscallRandFloat()),
          Map.entry(44, new SyscallRandDouble()),
          Map.entry(50, new SyscallConfirmDialog()),
          Map.entry(51, new SyscallInputDialogInt()),
          Map.entry(52, new SyscallInputDialogFloat()),
          Map.entry(53, new SyscallInputDialogDouble()),
          Map.entry(54, new SyscallInputDialogString()),
          Map.entry(55, new SyscallMessageDialog()),
          Map.entry(56, new SyscallMessageDialogInt()),
          Map.entry(57, new SyscallMessageDialogFloat()),
          Map.entry(58, new SyscallMessageDialogDouble()),
          Map.entry(59, new SyscallMessageDialogString()),
          Map.entry(60, new SyscallClearScreen()));

  static Syscall findByNumber(int number) {
    return BY_NUMBER.get(number);
  }

  public static void setEnvironment(SyscallEnvironment env) {
    BY_NUMBER.values().forEach(s -> s.setEnvironment(env));
  }

  private SyscallRegistry() {}
}
