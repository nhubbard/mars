package edu.missouristate.mars.api;

import edu.missouristate.mars.api.InstructionSourceMap.Location;
import edu.missouristate.mars.api.InstructionSourceMap.Origin;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The final symbol definitions from an assembled image, partitioned by assembly entry. */
public record AssemblySymbolMap(
    Map<Path, Map<String, Definition>> locals,
    Map<String, Definition> globals,
    Map<SourceToken, Set<MacroCall>> macroCalls,
    Map<SourceToken, Set<SymbolUse>> symbolUses) {

  public static final AssemblySymbolMap EMPTY = new AssemblySymbolMap(Map.of(), Map.of());

  public AssemblySymbolMap(Map<Path, Map<String, Definition>> locals, Map<String, Definition> globals) {
    this(locals, globals, Map.of());
  }

  public AssemblySymbolMap(
      Map<Path, Map<String, Definition>> locals, Map<String, Definition> globals,
      Map<SourceToken, Set<MacroCall>> macroCalls) {
    this(locals, globals, macroCalls, Map.of());
  }

  public AssemblySymbolMap {
    var copied = new HashMap<Path, Map<String, Definition>>();
    locals.forEach((entry, definitions) -> copied.put(entry, Map.copyOf(definitions)));
    locals = Map.copyOf(copied);
    globals = Map.copyOf(globals);
    var calls = new HashMap<SourceToken, Set<MacroCall>>();
    macroCalls.forEach((site, occurrences) -> calls.put(site, Set.copyOf(occurrences)));
    macroCalls = Map.copyOf(calls);
    var uses = new HashMap<SourceToken, Set<SymbolUse>>();
    symbolUses.forEach((site, bindings) -> uses.put(site, Set.copyOf(bindings)));
    symbolUses = Map.copyOf(uses);
  }

  /** A token at a one-based column in physical source, before macro and equivalence expansion. */
  public record SourceToken(Location location, int column) {}

  /** One realized label binding at a written operand, macro argument, or export. */
  public record SymbolUse(SourceToken definition, String name) {}

  /** One realized call shape at a written site. Columns are one-based in that site's source line. */
  public record MacroCall(
      SourceToken definition, String name, List<String> parameters, List<Integer> argumentColumns,
      Integer closingParenthesisColumn) {
    public MacroCall {
      parameters = List.copyOf(parameters);
      argumentColumns = List.copyOf(argumentColumns);
    }
  }

  /** A symbol's final address and source, with a one-based written column when available. */
  public record Definition(int address, Kind kind, Origin origin, boolean macroPrivate, Integer column) {
    public Definition(int address, Kind kind, Origin origin, boolean macroPrivate) {
      this(address, kind, origin, macroPrivate, null);
    }

    public Definition(int address, Kind kind, Origin origin) {
      this(address, kind, origin, false);
    }
  }

  public enum Kind {
    TEXT,
    DATA,
    EXTERNAL
  }
}
