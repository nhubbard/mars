package edu.missouristate.mars.api;

import java.nio.file.Path;

/**
 * A single assembler error or warning, carrying the source location and human-readable message.
 */
public record AssemblyError(Path file, int line, int column, String message, boolean isWarning) {

  @Override
  public String toString() {
    return (isWarning ? "warning" : "error") + " at " + file + ":" + line + ": " + message;
  }
}
