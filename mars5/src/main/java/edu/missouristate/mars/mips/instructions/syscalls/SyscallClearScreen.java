package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.*;

/**
 * Syscall 60: Clear the Run I/O console window in MARS (GUI only).
 *
 * <p>Usage in MIPS: li $v0, 60 syscall
 */
public class SyscallClearScreen extends AbstractSyscall {

  public SyscallClearScreen() {
    super(60, "ClearScreen");
  }

  @Override
  public void simulate(ProgramStatement statement) {
    Globals.getGui().clearRunConsole();
  }
}
