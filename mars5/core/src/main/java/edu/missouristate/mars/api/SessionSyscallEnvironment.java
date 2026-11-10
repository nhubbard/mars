package edu.missouristate.mars.api;

import static java.nio.charset.CodingErrorAction.REPLACE;

import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.instructions.syscalls.ToneGenerator;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Headless I/O owned by one session. File descriptors 0, 1, and 2 are the supplied console streams;
 * descriptors 3 through 31 are private to this environment. Closing or resetting files never closes
 * the console streams. Call only while the session is stopped or from its execution thread.
 */
public final class SessionSyscallEnvironment implements SyscallEnvironment, AutoCloseable {
  public enum ReadKind {
    INTEGER, FLOAT, DOUBLE, STRING, CHARACTER
  }

  /** Supplies typed reads without changing the raw stream used for descriptor 0. */
  @FunctionalInterface
  public interface TextInput {
    /** Returns a line without its terminator, or null on end of input. */
    String read(ReadKind kind, int maximumLength);
  }

  private final ToneGenerator tones;

  @Override
  public void playTone(int pitch, int duration, int instrument, int volume, boolean synchronous) {
    if (synchronous) {
      tones.generateToneSynchronously((byte) pitch, duration, (byte) instrument, (byte) volume);
    } else {
      tones.generateTone((byte) pitch, duration, (byte) instrument, (byte) volume);
    }
  }

  private final MemoryState memory;
  private final InputStream input;
  private final TextInput textInput;
  private final CharsetDecoder inputDecoder;
  private ByteBuffer inputBytes = ByteBuffer.allocate(8);
  private final CharBuffer inputChars = CharBuffer.allocate(8);
  private final PrintStream output;
  private final PrintStream error;
  private final Path workingDirectory;
  private final Closeable[] files = new Closeable[32];
  private final String[] paths = new String[32];

  public SessionSyscallEnvironment(MemoryState memory) {
    this(memory, System.in, System.out, System.err);
  }

  public SessionSyscallEnvironment(
      MemoryState memory, InputStream input, PrintStream output, PrintStream error) {
    this(memory, input, output, error, Path.of(""));
  }

  public SessionSyscallEnvironment(
      MemoryState memory, InputStream input, PrintStream output, PrintStream error,
      Path workingDirectory) {
    this(memory, input, output, error, workingDirectory, Charset.defaultCharset());
  }

  public SessionSyscallEnvironment(
      MemoryState memory, InputStream input, PrintStream output, PrintStream error,
      Path workingDirectory, Charset inputCharset) {
    this(memory, input, output, error, workingDirectory, inputCharset, null);
  }

  /** Uses the supplied text input for typed reads, or the console stream when it is null. */
  public SessionSyscallEnvironment(
      MemoryState memory, InputStream input, PrintStream output, PrintStream error,
      Path workingDirectory, Charset inputCharset, TextInput textInput) {
    this.memory = Objects.requireNonNull(memory);
    this.input = Objects.requireNonNull(input);
    this.textInput = textInput == null ? (kind, maximumLength) -> readLine() : textInput;
    this.inputDecoder = inputCharset.newDecoder().onMalformedInput(REPLACE).onUnmappableCharacter(REPLACE);
    this.output = Objects.requireNonNull(output);
    this.error = Objects.requireNonNull(error);
    this.tones = new ToneGenerator(failure -> this.error.println("MARS MIDI playback failed. " + failure));
    this.workingDirectory = Objects.requireNonNull(workingDirectory);
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
    var line = new StringBuilder();
    try {
      while (true) {
        int value = input.read();
        boolean end = value == -1;
        // Feed the decoder one byte at a time. A subsequent descriptor 0 read starts at the
        // next byte after this line, including when a newline occupies multiple bytes.
        if (!end) {
          if (!inputBytes.hasRemaining()) {
            inputBytes = ByteBuffer.allocate(inputBytes.capacity() * 2).put(inputBytes.flip());
          }
          inputBytes.put((byte) value);
        }
        inputBytes.flip();
        CoderResult result;
        do {
          result = inputDecoder.decode(inputBytes, inputChars, end);
          appendDecodedInput(line);
        } while (result.isOverflow());
        inputBytes.compact();
        if (end) {
          do {
            result = inputDecoder.flush(inputChars);
            appendDecodedInput(line);
          } while (result.isOverflow());
          inputDecoder.reset();
          if (line.isEmpty()) return null;
          break;
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\n') {
          line.setLength(line.length() - 1);
          break;
        }
      }
      if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
        line.setLength(line.length() - 1);
      }
      return line.toString();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read console input", e);
    }
  }

  private void appendDecodedInput(StringBuilder line) {
    line.append(inputChars.flip());
    inputChars.clear();
  }

  public String readString(int maxLength) {
    String line = textInput.read(ReadKind.STRING, maxLength);
    return line == null ? null : line.substring(0, Math.clamp(maxLength, 0, line.length()));
  }

  private String numericInput(ReadKind kind) {
    String line = textInput.read(kind, -1);
    return line == null ? "" : line.trim();
  }

  public int readInt() {
    return Integer.parseInt(numericInput(ReadKind.INTEGER));
  }

  public float readFloat() {
    return Float.parseFloat(numericInput(ReadKind.FLOAT));
  }

  public double readDouble() {
    return Double.parseDouble(numericInput(ReadKind.DOUBLE));
  }

  public char readChar() {
    String line = textInput.read(ReadKind.CHARACTER, 1);
    return (line == null ? "" : line).charAt(0);
  }

  public void exit(int code) {
    /* The instruction signals termination. */
  }

  public int openFile(String path, int flags) {
    if (path == null || (flags != 0 && flags != 1 && flags != 9)) {
      return -1;
    }
    for (int fd = 3; fd < files.length; fd++) {
      if (path.equals(paths[fd])) {
        return -1;
      }
    }
    for (int fd = 3; fd < files.length; fd++) {
      if (files[fd] != null) {
        continue;
      }
      try {
        File file = workingDirectory.resolve(path).toFile();
        files[fd] = flags == 0 ? new FileInputStream(file) : new FileOutputStream(file, flags == 9);
        paths[fd] = path;
        return fd;
      } catch (IOException | SecurityException | IllegalArgumentException e) {
        return -1;
      }
    }
    return -1;
  }

  public int readFromFile(int fd, byte[] buffer, int length) {
    if (buffer == null || length < 0 || length > buffer.length) {
      return -1;
    }
    InputStream stream =
        fd == 0
            ? input
            : fd >= 3 && fd < files.length && files[fd] instanceof InputStream in ? in : null;
    if (stream == null) {
      return -1;
    }
    try {
      return Math.max(0, stream.read(buffer, 0, length));
    } catch (IOException e) {
      return -1;
    }
  }

  public int writeToFile(int fd, byte[] buffer, int length) {
    if (buffer == null || length < 0 || length > buffer.length) {
      return -1;
    }
    OutputStream stream =
        fd == 1
            ? output
            : fd == 2
                ? error
                : fd >= 3 && fd < files.length && files[fd] instanceof OutputStream out
                    ? out
                    : null;
    if (stream == null) {
      return -1;
    }
    try {
      stream.write(buffer, 0, length);
      stream.flush();
      return stream instanceof PrintStream print && print.checkError() ? -1 : length;
    } catch (IOException e) {
      return -1;
    }
  }

  public void closeFile(int fd) {
    if (fd < 3 || fd >= files.length || files[fd] == null) {
      return;
    }
    try {
      files[fd].close();
    } catch (IOException ignored) {
    } finally {
      files[fd] = null;
      paths[fd] = null;
    }
  }

  /** Stops asynchronous tones and closes open files. The environment can subsequently be reused. */
  public void close() {
    tones.close();
    for (int fd = 3; fd < files.length; fd++) {
      closeFile(fd);
    }
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
