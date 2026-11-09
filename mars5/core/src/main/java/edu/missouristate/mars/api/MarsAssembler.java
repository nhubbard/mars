package edu.missouristate.mars.api;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Contract for assembling one or more MIPS source files into an executable program. Implementations
 * must be headless — no GUI interaction of any kind.
 */
public interface MarsAssembler {

  /**
   * Assembles the given source files.
   *
   * @param sources ordered list of source files; the first file is the entry point
   * @return an {@link AssemblyResult} that is either a successful program or a list of errors
   */
  AssemblyResult assemble(List<Path> sources);

  /**
   * Assembles the given source files with an optional exception-handler file prepended.
   *
   * @param sources ordered list of source files
   * @param exceptionHandler path to an exception-handler source file, or {@code null} for none
   * @param extendedAssemblerEnabled {@code true} to allow pseudo-instructions
   * @param warningsAreErrors {@code true} to treat assembler warnings as errors
   * @return an {@link AssemblyResult}
   */
  AssemblyResult assemble(
      List<Path> sources, Path exceptionHandler, boolean extendedAssemblerEnabled,
      boolean warningsAreErrors);

  /**
   * Assembles captured text for the ordered entries, optional exception handler, and all
   * includes. An uncaptured source produces an assembly diagnostic.
   */
  default AssemblyResult assembleSnapshot(
      AssemblySourceSnapshot snapshot,
      Path exceptionHandler,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors) {
    return assembleSnapshot(
        snapshot, exceptionHandler, extendedAssemblerEnabled, warningsAreErrors, () -> {});
  }

  /**
   * Assembles captured sources with cooperative cancellation. The check runs on the calling
   * thread and may throw {@link CancellationException} to abort. Use a
   * separate session if a previously assembled program must remain available after cancellation.
   */
  AssemblyResult assembleSnapshot(
      AssemblySourceSnapshot snapshot,
      Path exceptionHandler,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors,
      Runnable checkCancellation);
}
