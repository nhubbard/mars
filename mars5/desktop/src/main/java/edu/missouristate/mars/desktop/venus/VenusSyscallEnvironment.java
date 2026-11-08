package edu.missouristate.mars.desktop.venus;

import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.api.SyscallEnvironment;
import edu.missouristate.mars.desktop.runtime.DesktopSession;
import java.nio.charset.Charset;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Desktop console/dialog routing with the owning session's file table, heap, and audio. */
public final class VenusSyscallEnvironment implements SyscallEnvironment {
  private final Settings settings;
  private final MarsGuiProxy gui;
  private final SessionSyscallEnvironment files;

  public VenusSyscallEnvironment(DesktopSession session) {
    settings = session.getSettings();
    gui = session.getGui();
    files = session.context().syscallEnvironment;
  }

  public void printString(String text) {
    gui.postRunMessage(text);
  }

  public void printInt(int value) {
    printString(Integer.toString(value));
  }

  public void printFloat(float value) {
    printString(Float.toString(value));
  }

  public void printDouble(double value) {
    printString(Double.toString(value));
  }

  public void printChar(char value) {
    printString(String.valueOf(value));
  }

  private String input(String prompt, int maximum) {
    return settings.getBooleanSetting(Settings.POPUP_SYSCALL_INPUT)
        ? onEventThread(() -> gui.readInputWithPrompt(prompt))
        : gui.readInput(maximum);
  }

  public int readInt() {
    return Integer.parseInt(input("Enter an integer value (syscall 5)", -1).trim());
  }

  public float readFloat() {
    return Float.parseFloat(input("Enter a float value (syscall 6)", -1).trim());
  }

  public double readDouble() {
    return Double.parseDouble(input("Enter a double value (syscall 7)", -1).trim());
  }

  public char readChar() {
    return input("Enter a character value (syscall 12)", 1).charAt(0);
  }

  public String readString(int maximum) {
    String value = input("Enter a string of maximum length " + maximum + " (syscall 8)", maximum);
    if (value == null) return null;
    if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
    return value.substring(0, Math.clamp(maximum, 0, value.length()));
  }

  public void exit(int code) {}

  public int openFile(String path, int flags) {
    return files.openFile(path, flags);
  }

  public int readFromFile(int fd, byte[] buffer, int length) {
    if (fd != 0) return files.readFromFile(fd, buffer, length);
    if (buffer == null || length < 0 || length > buffer.length) return -1;
    if (length == 0) return 0;
    String value = gui.readInput(length);
    if (value == null) return 0;
    byte[] bytes = value.getBytes(Charset.defaultCharset());
    int count = Math.min(length, bytes.length);
    System.arraycopy(bytes, 0, buffer, 0, count);
    return count;
  }

  public int writeToFile(int fd, byte[] buffer, int length) {
    if (fd != 1 && fd != 2) return files.writeToFile(fd, buffer, length);
    if (buffer == null || length < 0 || length > buffer.length) return -1;
    printString(new String(buffer, 0, length, Charset.defaultCharset()));
    return length;
  }

  public void closeFile(int fd) {
    files.closeFile(fd);
  }

  public int sbrk(int bytes) {
    return files.sbrk(bytes);
  }

  public void playTone(int pitch, int duration, int instrument, int volume, boolean synchronous) {
    files.playTone(pitch, duration, instrument, volume, synchronous);
  }

  public void showMessageDialog(String text, String title, int type) {
    onEventThread(
        () -> {
          JOptionPane.showMessageDialog(null, text, title, type);
          return null;
        });
  }

  public String showInputDialog(String text) {
    return onEventThread(() -> JOptionPane.showInputDialog(text));
  }

  public int showConfirmDialog(String text) {
    return onEventThread(() -> JOptionPane.showConfirmDialog(null, text));
  }

  public void clearConsole() {
    onEventThread(
        () -> {
          gui.clearRunConsole();
          return null;
        });
  }

  private static <T> T onEventThread(Supplier<T> action) {
    if (SwingUtilities.isEventDispatchThread()) return action.get();
    var task = new FutureTask<>(action::get);
    SwingUtilities.invokeLater(task);
    try {
      return task.get();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for desktop input", error);
    } catch (java.util.concurrent.ExecutionException error) {
      throw new IllegalStateException(error.getCause());
    }
  }
}
