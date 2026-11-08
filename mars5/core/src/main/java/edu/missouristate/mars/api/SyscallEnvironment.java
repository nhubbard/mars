package edu.missouristate.mars.api;

/**
 * Abstracts all I/O that MIPS syscalls need to perform. The core simulator calls this interface;
 * concrete implementations decide where output goes (stdout, an IDE console, a test buffer, etc.)
 * and where input comes from (stdin, a pre-supplied string, etc.).
 *
 * <p>Decoupling syscalls from concrete I/O channels allows the same simulator core to be used
 * headlessly in tests, from the CLI, and inside an IDE plugin without any conditional logic inside
 * the syscall implementations themselves.
 *
 * <p>All methods are called on the simulator thread and must not interact with Swing or any other
 * UI toolkit directly.
 */
public interface SyscallEnvironment {

  // -------------------------------------------------------------------------
  // Console I/O
  // -------------------------------------------------------------------------

  void printString(String s);

  void printInt(int value);

  void printFloat(float value);

  void printDouble(double value);

  void printChar(char c);

  /** Reads a line of text. Returns the text (without trailing newline), or {@code null} on EOF. */
  String readString(int maxLength);

  int readInt();

  float readFloat();

  double readDouble();

  char readChar();

  // -------------------------------------------------------------------------
  // Process control
  // -------------------------------------------------------------------------

  /**
   * Terminates the simulated program with the given exit code. Implementations should signal the
   * simulator engine to stop after returning from this call.
   *
   * @param code the exit code (0 = success)
   */
  void exit(int code);

  // -------------------------------------------------------------------------
  // File I/O
  // -------------------------------------------------------------------------

  /**
   * Opens a file.
   *
   * @param path file path
   * @param flags O_RDONLY=0, O_WRONLY=1; O_WRONLY|O_APPEND=9
   * @return a non-negative file descriptor on success, or -1 on failure
   */
  int openFile(String path, int flags);

  /**
   * Reads up to {@code lengthRequested} bytes from {@code fd} into {@code buffer}.
   *
   * @return number of bytes read, 0 on EOF, or -1 on error
   */
  int readFromFile(int fd, byte[] buffer, int lengthRequested);

  /**
   * Writes {@code lengthRequested} bytes from {@code buffer} to {@code fd}.
   *
   * @return number of bytes written, or -1 on error
   */
  int writeToFile(int fd, byte[] buffer, int lengthRequested);

  /** Closes the open file identified by {@code fd}. */
  void closeFile(int fd);

  // -------------------------------------------------------------------------
  // Heap allocation
  // -------------------------------------------------------------------------

  /**
   * Allocates {@code bytes} bytes on the simulated heap.
   *
   * @return the base address of the allocated region
   */
  int sbrk(int bytes);

  // -------------------------------------------------------------------------
  // Dialog I/O (GUI: modal dialog; headless: stderr/stdin)
  // -------------------------------------------------------------------------

  /**
   * Shows a message dialog. {@code messageType}: 0=ERROR, 1=INFO, 2=WARNING, 3=QUESTION, -1=PLAIN
   */
  void showMessageDialog(String message, String title, int messageType);

  /** Shows an input dialog. Returns the entered string, or {@code null} if cancelled. */
  String showInputDialog(String message);

  /** Shows a YES/NO/CANCEL confirm dialog. Returns 0=YES, 1=NO, 2=CANCEL. */
  int showConfirmDialog(String message);

  // -------------------------------------------------------------------------
  // Console control
  // -------------------------------------------------------------------------

  /** Clears the run I/O console (no-op in headless mode). */
  void clearConsole();

  /** Plays a normalized MIDI tone; frontends without audio support may keep the default silence. */
  default void playTone(int pitch, int duration, int instrument, int volume, boolean synchronous) {}
}
