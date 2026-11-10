package edu.missouristate.mars.api;

import edu.missouristate.mars.api.InstructionSourceMap.Location;
import edu.missouristate.mars.api.InstructionSourceMap.Origin;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** The final symbol definitions from an assembled image, partitioned by assembly entry. */
public record AssemblySymbolMap(
    Map<Path, Map<String, Definition>> locals,
    Map<String, Definition> globals,
    Map<SourceToken, Set<SourceToken>> nestedMacroTargets) {

  public static final AssemblySymbolMap EMPTY = new AssemblySymbolMap(Map.of(), Map.of());

  public AssemblySymbolMap(Map<Path, Map<String, Definition>> locals, Map<String, Definition> globals) {
    this(locals, globals, Map.of());
  }

  public AssemblySymbolMap {
    var copied = new HashMap<Path, Map<String, Definition>>();
    locals.forEach((entry, definitions) -> copied.put(entry, Map.copyOf(definitions)));
    locals = Map.copyOf(copied);
    globals = Map.copyOf(globals);
    var calls = new HashMap<SourceToken, Set<SourceToken>>();
    nestedMacroTargets.forEach((site, targets) -> calls.put(site, Set.copyOf(targets)));
    nestedMacroTargets = Map.copyOf(calls);
  }

  /** A token at a one-based column in physical source, before macro and equivalence expansion. */
  public record SourceToken(Location location, int column) {}

  /** A symbol's final address and source. Macro-private names were rewritten for one invocation. */
  public record Definition(int address, Kind kind, Origin origin, boolean macroPrivate) {
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
