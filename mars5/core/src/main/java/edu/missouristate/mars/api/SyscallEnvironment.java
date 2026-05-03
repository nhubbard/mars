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
   * @param flags O_RDONLY=0, O_WRONLY=1, O_RDWR=2; OR'd with O_CREAT=0x200, O_TRUNC=0x400, etc.
   * @return a non-negative file descriptor on success, or a negative error code on failure
   */
  int openFile(String path, int flags);

  /**
   * Reads up to {@code maxBytes} bytes from the open file identified by {@code fd} into a
   * byte-array buffer returned as a {@code String}.
   *
   * @return string of bytes read, or {@code null} on error
   */
  String readFile(int fd, int maxBytes);

  /**
   * Writes the given bytes to the open file identified by {@code fd}.
   *
   * @return number of bytes written, or negative error code
   */
  int writeFile(int fd, byte[] data);

  /**
   * Closes the open file identified by {@code fd}.
   */
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
}
