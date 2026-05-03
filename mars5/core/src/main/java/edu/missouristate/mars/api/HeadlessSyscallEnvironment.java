package edu.missouristate.mars.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Headless implementation of {@link SyscallEnvironment} for CLI/batch use. */
public class HeadlessSyscallEnvironment extends BaseSyscallEnvironment {

  @Override
  public void showMessageDialog(String message, String title, int messageType) {
    System.err.println((title != null && !title.isEmpty() ? "[" + title + "] " : "") + message);
  }

  @Override
  public String showInputDialog(String message) {
    System.err.print(message + ": ");
    System.err.flush();
    try {
      return new BufferedReader(new InputStreamReader(System.in)).readLine();
    } catch (IOException e) {
      return "";
    }
  }

  @Override
  public int showConfirmDialog(String message) {
    return 0; // YES — safe default for non-interactive use
  }

  @Override
  public void clearConsole() {
    // no-op in headless mode
  }
}
