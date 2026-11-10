package edu.missouristate.mars.assembler;

import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken;
import edu.missouristate.mars.api.AssemblySymbolMap.SymbolUse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Collects the physical origin of each symbol lookup shared by both assembly passes. */
final class AssemblySymbolUses {
  private final Map<SourceToken, Set<SymbolUse>> bindings = new HashMap<>();

  Map<SourceToken, Set<SymbolUse>> bindings() {
    return bindings;
  }

  void record(Token operand, Symbol target) {
    if (target.origin() == null || target.column() == null) {
      return;
    }
    var definition = new SourceToken(target.origin().location(), target.column());
    bindings.computeIfAbsent(operand.diagnosticSite(), ignored -> new HashSet<>())
        .add(new SymbolUse(definition, target.getName(), operand.equivalenceSource()));
  }
}
