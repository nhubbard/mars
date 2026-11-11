package edu.missouristate.mars.assembler;

import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken;
import edu.missouristate.mars.api.AssemblySymbolMap.SymbolUse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Collects the physical origin of each symbol lookup shared by both assembly passes. */
final class AssemblySymbolUses {
  private record Binding(Symbol target, SourceToken equivalenceSource) {}

  private final Map<SourceToken, Set<Binding>> bindings = new HashMap<>();

  Map<SourceToken, Set<SymbolUse>> bindings(Runnable checkCancellation) {
    var result = new HashMap<SourceToken, Set<SymbolUse>>();
    bindings.forEach((site, uses) -> {
      var detached = new HashSet<SymbolUse>();
      for (var use : uses) {
        checkCancellation.run();
        var target = use.target();
        var definition = new SourceToken(target.origin().location(), target.column());
        detached.add(new SymbolUse(definition, target.getName(), target.getAddress(), use.equivalenceSource()));
      }
      result.put(site, detached);
    });
    return result;
  }

  void record(Token operand, Symbol target) {
    if (target.origin() == null || target.column() == null) {
      return;
    }
    bindings.computeIfAbsent(operand.diagnosticSite(), ignored -> new HashSet<>())
        .add(new Binding(target, operand.equivalenceSource()));
  }
}
