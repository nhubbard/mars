package edu.missouristate.mars.core;

import edu.missouristate.mars.ErrorList;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.api.*;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Public assembly API backed by one session, preserving source diagnostics. */
@Singleton
public final class SessionAssembler implements MarsAssembler {
  private final MarsContext session;

  @Inject
  public SessionAssembler(MarsContext session) {
    this.session = session;
  }

  @Override
  public AssemblyResult assemble(List<Path> sources) {
    return assemble(
        sources,
        null,
        session.settings.isExtendedAssemblerEnabled(),
        session.settings.isWarningsAreErrors());
  }

  @Override
  public AssemblyResult assemble(
      List<Path> sources,
      Path exceptionHandler,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors) {
    try {
      var program =
          session.assemble(sources, exceptionHandler, extendedAssemblerEnabled, warningsAreErrors);
      return new AssemblyResult(program, diagnostics(program.getAssemblyDiagnostics()));
    } catch (ProcessingException error) {
      return new AssemblyResult(null, diagnostics(error.errors()));
    }
  }

  @Override
  public AssemblyResult assembleSnapshot(
      AssemblySourceSnapshot snapshot,
      Path exceptionHandler,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors,
      Runnable checkCancellation) {
    try {
      var program =
          session.assembleSnapshot(
              snapshot, exceptionHandler, extendedAssemblerEnabled, warningsAreErrors,
              checkCancellation);
      return new AssemblyResult(program, diagnostics(program.getAssemblyDiagnostics()));
    } catch (ProcessingException error) {
      return new AssemblyResult(null, diagnostics(error.errors()));
    }
  }

  private static List<AssemblyError> diagnostics(ErrorList errors) {
    return errors.getErrorMessages().stream()
        .map(
            message ->
                new AssemblyError(
                    message.getFilename() == null || message.getFilename().isEmpty()
                        ? null
                        : Path.of(message.getFilename()),
                    message.getLine(),
                    message.getPosition(),
                    message.getMessage(),
                    message.isWarning()))
        .toList();
  }
}
