package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.InstructionSourceMap;
import edu.missouristate.mars.core.*;
import java.util.*;

/** Orchestrates symbol/directive processing, pseudo expansion and machine-code emission. */
public final class Assembler {
  private final AssemblyContext context;
  private ErrorList errors;
  private InstructionSourceMap sourceMap = InstructionSourceMap.EMPTY;

  public Assembler(MarsContext session) {
    this(new AssemblyContext(session));
  }

  public Assembler(AssemblyContext context) {
    this.context = Objects.requireNonNull(context);
  }

  public ErrorList getErrorList() {
    return errors;
  }

  public InstructionSourceMap getSourceMap() {
    return sourceMap;
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
    return assemble(programs, extended, warnings, () -> {});
  }

  public ArrayList<ProgramStatement> assemble(
      ArrayList<MIPSprogram> programs, boolean extended, boolean warnings, Runnable checkCancellation)
      throws ProcessingException {
    try (var lease = context.session().claimStoppedSession()) {
      sourceMap = InstructionSourceMap.EMPTY;
      var first = new FirstPassProcessor(context, checkCancellation);
      try {
        errors = first.process(programs, extended, warnings);
      } catch (ProcessingException failure) {
        errors = failure.errors();
        throw failure;
      }
      var result =
          new SecondPassProcessor(context, checkCancellation).process(programs, errors, warnings);
      sourceMap = result.sourceMap();
      return result.instructions();
    }
  }
}
