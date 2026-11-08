package edu.missouristate.mars.api;

import java.util.List;

/**
 * The outcome of a single assembly operation. Either the program assembled successfully (in which
 * case {@link #errors()} contains at most warnings) or it failed (in which case {@link #program()}
 * is {@code null} and {@link #errors()} is non-empty).
 */
public record AssemblyResult(Program program, List<AssemblyError> errors) {

  public AssemblyResult {
    errors = List.copyOf(errors);
  }

  /** Returns {@code true} if assembly completed without any errors. */
  public boolean succeeded() {
    return program != null && errors.stream().noneMatch(error -> !error.isWarning());
  }
}
