package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.*;
// FIXME(phase-2): gui-coupling — JOptionPane/Swing dialog syscall; replace with SyscallEnvironment
// call in Phase 2
import javax.swing.*;

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
    // Only works when GUI is active
    if (Globals.getGui() == null) return;

    JTextArea runConsole = Globals.getGui().getMessagesPane().getRunTextArea();

    if (runConsole != null) {
      SwingUtilities.invokeLater(() -> runConsole.setText(""));
    }
  }
}
