package edu.missouristate.mars.core;

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
      return new AssemblyResult(
          session.assemble(sources, exceptionHandler, extendedAssemblerEnabled, warningsAreErrors),
          List.of());
    } catch (ProcessingException error) {
      var errors =
          error.errors().getErrorMessages().stream()
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
      return new AssemblyResult(null, errors);
    }
  }
}
