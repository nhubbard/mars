package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.core.*;
import java.util.*;

/** Orchestrates symbol/directive processing, pseudo expansion and machine-code emission. */
public final class Assembler {
  private final AssemblyContext context;
  private ErrorList errors;

  public Assembler(MarsContext session) {
    this(new AssemblyContext(session));
  }

  public Assembler(AssemblyContext context) {
    this.context = Objects.requireNonNull(context);
  }

  public ErrorList getErrorList() {
    return errors;
  }

  public ArrayList<ProgramStatement> assemble(MIPSprogram program, boolean extended)
      throws ProcessingException {
    return assemble(program, extended, false);
  }

  public ArrayList<ProgramStatement> assemble(
      MIPSprogram program, boolean extended, boolean warnings) throws ProcessingException {
    return assemble(new ArrayList<>(List.of(program)), extended, warnings);
  }

  public ArrayList<ProgramStatement> assemble(ArrayList<MIPSprogram> programs, boolean extended)
      throws ProcessingException {
    return assemble(programs, extended, false);
  }

  public ArrayList<ProgramStatement> assemble(
      ArrayList<MIPSprogram> programs, boolean extended, boolean warnings)
      throws ProcessingException {
    try (var lease = context.session().claimStoppedSession()) {
      var first = new FirstPassProcessor(context);
      try {
        errors = first.process(programs, extended, warnings);
      } catch (ProcessingException failure) {
        errors = failure.errors();
        throw failure;
      }
      return new SecondPassProcessor(context).process(programs, errors, warnings);
    }
  }
}
