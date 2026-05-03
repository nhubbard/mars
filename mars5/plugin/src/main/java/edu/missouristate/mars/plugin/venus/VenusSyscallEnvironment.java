package edu.missouristate.mars.plugin.venus;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.api.BaseSyscallEnvironment;
import javax.swing.JOptionPane;

/** GUI (Venus IDE) implementation of the syscall I/O environment. */
public class VenusSyscallEnvironment extends BaseSyscallEnvironment {

  @Override
  public void showMessageDialog(String message, String title, int messageType) {
    JOptionPane.showMessageDialog(null, message, title, messageType);
  }

  @Override
  public String showInputDialog(String message) {
    return JOptionPane.showInputDialog(message);
  }

  @Override
  public int showConfirmDialog(String message) {
    return JOptionPane.showConfirmDialog(null, message);
  }

  @Override
  public void clearConsole() {
    Globals.getGui().clearRunConsole();
  }
}
