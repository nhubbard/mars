package edu.missouristate.mars.api;

import edu.missouristate.mars.mips.hardware.MemoryState;
import java.io.*;
import java.util.Objects;

/**
 * Headless I/O owned by one session. File descriptors 0, 1, and 2 are the supplied console streams;
 * descriptors 3 through 31 are private to this environment. Closing or resetting files never closes
 * the console streams. Call only while the session is stopped or from its execution thread.
 */
public final class SessionSyscallEnvironment implements SyscallEnvironment, AutoCloseable {
  private final MemoryState memory;
  private final InputStream input;
  private final PrintStream output;
  private final PrintStream error;
  private final Closeable[] files = new Closeable[32];
  private final String[] paths = new String[32];

  public SessionSyscallEnvironment(MemoryState memory) {
    this(memory, System.in, System.out, System.err);
  }

  public SessionSyscallEnvironment(
      MemoryState memory, InputStream input, PrintStream output, PrintStream error) {
    this.memory = Objects.requireNonNull(memory);
    this.input = Objects.requireNonNull(input);
    this.output = Objects.requireNonNull(output);
    this.error = Objects.requireNonNull(error);
  }

  public void printString(String value) {
    output.print(value);
    output.flush();
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

  private String readLine() {
    try {
      var bytes = new ByteArrayOutputStream();
      int value;
      while ((value = input.read()) != -1 && value != '\n') bytes.write(value);
      if (value == -1 && bytes.size() == 0) return null;
      String line = bytes.toString(java.nio.charset.Charset.defaultCharset());
      return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read console input", e);
    }
  }

  public String readString(int maxLength) {
    String line = readLine();
    return line == null ? null : line.substring(0, Math.min(line.length(), Math.max(0, maxLength)));
  }

  private String numericInput() {
    String line = readLine();
    return line == null ? "" : line.trim();
  }

  public int readInt() {
    return Integer.parseInt(numericInput());
  }

  public float readFloat() {
    return Float.parseFloat(numericInput());
  }

  public double readDouble() {
    return Double.parseDouble(numericInput());
  }

  public char readChar() {
    String line = readLine();
    return (line == null ? "" : line).charAt(0);
  }

  public void exit(int code) {
    /* The instruction signals termination. */
  }

  public int openFile(String path, int flags) {
    if (path == null || (flags != 0 && flags != 1 && flags != 9)) return -1;
    for (int fd = 3; fd < files.length; fd++) if (path.equals(paths[fd])) return -1;
    for (int fd = 3; fd < files.length; fd++) {
      if (files[fd] != null) continue;
      try {
        files[fd] = flags == 0 ? new FileInputStream(path) : new FileOutputStream(path, flags == 9);
        paths[fd] = path;
        return fd;
      } catch (IOException | SecurityException e) {
        return -1;
      }
    }
    return -1;
  }

  public int readFromFile(int fd, byte[] buffer, int length) {
    if (buffer == null || length < 0 || length > buffer.length) return -1;
    InputStream stream =
        fd == 0
            ? input
            : fd >= 3 && fd < files.length && files[fd] instanceof InputStream in ? in : null;
    if (stream == null) return -1;
    try {
      return Math.max(0, stream.read(buffer, 0, length));
    } catch (IOException e) {
      return -1;
    }
  }

  public int writeToFile(int fd, byte[] buffer, int length) {
    if (buffer == null || length < 0 || length > buffer.length) return -1;
    OutputStream stream =
        fd == 1
            ? output
            : fd == 2
                ? error
                : fd >= 3 && fd < files.length && files[fd] instanceof OutputStream out
                    ? out
                    : null;
    if (stream == null) return -1;
    try {
      stream.write(buffer, 0, length);
      stream.flush();
      return stream instanceof PrintStream print && print.checkError() ? -1 : length;
    } catch (IOException e) {
      return -1;
    }
  }

  public void closeFile(int fd) {
    if (fd < 3 || fd >= files.length || files[fd] == null) return;
    try {
      files[fd].close();
    } catch (IOException ignored) {
    } finally {
      files[fd] = null;
      paths[fd] = null;
    }
  }

  /** Releases this environment's open files; the environment can subsequently be reused. */
  public void close() {
    for (int fd = 3; fd < files.length; fd++) closeFile(fd);
  }

  public int sbrk(int bytes) {
    return memory.allocateBytesFromHeap(bytes);
  }

  public void showMessageDialog(String message, String title, int type) {
    error.println((title != null && !title.isEmpty() ? "[" + title + "] " : "") + message);
  }

  public String showInputDialog(String message) {
    error.print(message + ": ");
    error.flush();
    return readLine();
  }

  public int showConfirmDialog(String message) {
    return 0;
  }

  public void clearConsole() {}
}
