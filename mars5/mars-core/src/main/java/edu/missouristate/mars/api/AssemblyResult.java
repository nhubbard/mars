package edu.missouristate.mars.api;

import java.util.List;

/**
 * The outcome of a single assembly operation. Either the program assembled successfully (in which
 * case {@link #errors()} is empty) or it failed (in which case {@link #program()} is {@code null}
 * and {@link #errors()} is non-empty).
 */
public record AssemblyResult(Program program, List<AssemblyError> errors) {

  /** Returns {@code true} if assembly completed without any errors. */
  public boolean succeeded() {
    return errors.isEmpty();
  }
}
