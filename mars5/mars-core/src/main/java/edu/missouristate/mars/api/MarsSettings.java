package edu.missouristate.mars.api;

/**
 * Headless-safe subset of MARS simulation settings. Only contains settings that affect simulation
 * behavior, not GUI preferences (fonts, colors, window sizes). Implementations must be safe to
 * instantiate without a running Swing environment.
 */
public interface MarsSettings {

  /** Returns the active memory configuration identifier (e.g. {@code "Default"}). */
  String getMemoryConfiguration();

  /** Returns {@code true} if delayed branching is enabled. */
  boolean isDelayedBranchingEnabled();

  /** Returns {@code true} if self-modifying code is enabled. */
  boolean isSelfModifyingCodeEnabled();

  /** Returns {@code true} if assembler warnings are treated as errors. */
  boolean isWarningsAreErrors();

  /** Returns the maximum number of assembler errors to report per assembly operation. */
  int getAssemblerErrorLimit();

  /** Returns {@code true} if the extended (pseudo) instruction assembler is enabled. */
  boolean isExtendedAssemblerEnabled();
}
