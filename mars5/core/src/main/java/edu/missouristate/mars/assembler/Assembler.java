package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.AssemblySymbolMap;
import edu.missouristate.mars.api.AssemblySymbolMap.Definition;
import edu.missouristate.mars.api.InstructionSourceMap;
import edu.missouristate.mars.core.*;
import java.nio.file.Path;
import java.util.*;

/** Orchestrates symbol/directive processing, pseudo expansion and machine-code emission. */
public final class Assembler {
  private final AssemblyContext context;
  private ErrorList errors;
  private InstructionSourceMap sourceMap = InstructionSourceMap.EMPTY;
  private AssemblySymbolMap symbolMap = AssemblySymbolMap.EMPTY;

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

  public AssemblySymbolMap getSymbolMap() {
    return symbolMap;
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
      symbolMap = AssemblySymbolMap.EMPTY;
      var symbolUses = new AssemblySymbolUses();
      var first = new FirstPassProcessor(context, checkCancellation, symbolUses);
      try {
        errors = first.process(programs, extended, warnings);
      } catch (ProcessingException failure) {
        errors = failure.errors();
        throw failure;
      }
      var result =
          new SecondPassProcessor(context, checkCancellation, symbolUses).process(programs, errors, warnings);
      var locals = new HashMap<Path, Map<String, Definition>>();
      for (var program : programs) {
        checkCancellation.run();
        var entry = Path.of(program.getFilename()).toAbsolutePath().normalize();
        locals.put(entry, definitions(program.getLocalSymbolTable(), checkCancellation));
      }
      symbolMap = new AssemblySymbolMap(
          locals, definitions(context.symbols(), checkCancellation), first.macroCalls, symbolUses.bindings());
      sourceMap = result.sourceMap();
      return result.instructions();
    }
  }

  private static Map<String, Definition> definitions(SymbolTable table, Runnable checkCancellation) {
    var result = new HashMap<String, Definition>();
    for (var symbol : table.getAllSymbols()) {
      checkCancellation.run();
      result.put(symbol.getName(), new Definition(
          symbol.getAddress(), symbol.kind(), symbol.origin(), symbol.macroPrivate(), symbol.column()));
    }
    return result;
  }
}
