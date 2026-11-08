package edu.missouristate.mars.api;

/** Carries information about why simulator execution stopped. */
public record StopEvent(Reason reason, int exitCode) {

  /** The cause of a simulation stop. */
  public enum Reason {
    /** Program executed syscall 10 (exit) or syscall 17 (exit2). */
    NORMAL_EXIT,
    /** Caller explicitly stopped execution. */
    STOP_REQUEST,
    /** Execution halted at a breakpoint address. */
    BREAKPOINT,
    /** Caller requested a pause (e.g. the IDE paused the run). */
    PAUSE,
    /** Maximum step limit was reached. */
    STEP_LIMIT,
    /** Program counter ran off the bottom of the text segment with no halt. */
    CLIFF_TERMINATION,
    /** A run-time exception occurred (also triggers {@code onError}). */
    EXCEPTION,
  }
}
