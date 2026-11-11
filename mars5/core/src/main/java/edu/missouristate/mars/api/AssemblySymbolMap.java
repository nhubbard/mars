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
    Map<SourceToken, Set<SymbolUse>> symbolUses,
    Map<Path, List<MacroDefinition>> macros,
    Map<Path, Set<EquivalenceOccurrence>> equivalences) {

  public static final AssemblySymbolMap EMPTY = new AssemblySymbolMap(Map.of(), Map.of());

  public AssemblySymbolMap(Map<Path, Map<String, Definition>> locals, Map<String, Definition> globals) {
    this(locals, globals, Map.of());
  }

  public AssemblySymbolMap(
      Map<Path, Map<String, Definition>> locals, Map<String, Definition> globals,
      Map<SourceToken, Set<MacroCall>> macroCalls) {
    this(locals, globals, macroCalls, Map.of());
  }

  public AssemblySymbolMap(
      Map<Path, Map<String, Definition>> locals, Map<String, Definition> globals,
      Map<SourceToken, Set<MacroCall>> macroCalls, Map<SourceToken, Set<SymbolUse>> symbolUses) {
    this(locals, globals, macroCalls, symbolUses, Map.of());
  }

  public AssemblySymbolMap(
      Map<Path, Map<String, Definition>> locals, Map<String, Definition> globals,
      Map<SourceToken, Set<MacroCall>> macroCalls, Map<SourceToken, Set<SymbolUse>> symbolUses,
      Map<Path, List<MacroDefinition>> macros) {
    this(locals, globals, macroCalls, symbolUses, macros, Map.of());
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
    var definitions = new HashMap<Path, List<MacroDefinition>>();
    macros.forEach((entry, entries) -> definitions.put(entry, List.copyOf(entries)));
    macros = Map.copyOf(definitions);
    var aliases = new HashMap<Path, Set<EquivalenceOccurrence>>();
    equivalences.forEach((entry, occurrences) -> aliases.put(entry, Set.copyOf(occurrences)));
    equivalences = Map.copyOf(aliases);
  }

  /** A token at a one-based column in physical source, before macro and equivalence expansion. */
  public record SourceToken(Location location, int column) {}

  /** The effective definition and written spelling of one .eqv declaration or substitution. */
  public record EquivalenceOccurrence(
      SourceToken site, SourceToken spelling, SourceToken definition, String name, boolean declaration) {}

  /** A label binding with its final address and optional written .eqv replacement. */
  public record SymbolUse(SourceToken definition, String name, int address, SourceToken equivalenceSource) {
    public SymbolUse(SourceToken definition, String name, int address) {
      this(definition, name, address, null);
    }
  }

  /** One realized call shape at a written site. Columns are one-based in that site's source line. */
  public record MacroCall(
      SourceToken definition, String name, List<String> parameters, List<Integer> argumentColumns,
      Integer closingParenthesisColumn, SourceToken spelling) {
    public MacroCall {
      parameters = List.copyOf(parameters);
      argumentColumns = List.copyOf(argumentColumns);
    }

    public MacroCall(SourceToken definition, String name, List<String> parameters,
        List<Integer> argumentColumns, Integer closingParenthesisColumn) {
      this(definition, name, parameters, argumentColumns, closingParenthesisColumn, null);
    }
  }

  /** A committed macro signature, including definitions without realized calls. */
  public record MacroDefinition(SourceToken site, String name, List<String> parameters) {
    public MacroDefinition {
      parameters = List.copyOf(parameters);
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
