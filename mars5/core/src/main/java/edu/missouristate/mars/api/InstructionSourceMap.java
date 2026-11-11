package edu.missouristate.mars.api;

import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken;
import edu.missouristate.mars.assembler.TokenTypes;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Source locations and line breakpoint addresses for one assembled image. Each breakpoint list
 * contains the first emitted address of each occurrence of that physical line.
 */
public record InstructionSourceMap(
    Map<Integer, Origin> origins, Map<Location, List<Integer>> breakpoints, Set<Integer> pseudoAddresses) {

  public static final InstructionSourceMap EMPTY = new InstructionSourceMap(Map.of(), Map.of());

  /** An expanded operand, its written token, and the macro arguments from inner to outer call. */
  public record SourceOperand(
      SourceToken written, List<SourceToken> arguments, String value, TokenTypes type, boolean coprocessor0) {
    public SourceOperand {
      arguments = List.copyOf(arguments);
    }
  }

  public InstructionSourceMap(Map<Integer, Origin> origins, Map<Location, List<Integer>> breakpoints) {
    this(origins, breakpoints, Set.of());
  }

  public InstructionSourceMap {
    origins = Map.copyOf(origins);
    var copied = new HashMap<Location, List<Integer>>();
    breakpoints.forEach((location, addresses) -> copied.put(location, List.copyOf(addresses)));
    breakpoints = Map.copyOf(copied);
    pseudoAddresses = Set.copyOf(pseudoAddresses);
  }

  /** A physical line in an assembly source file. Line numbering starts at one. */
  public record Location(Path file, int line) {
    public Location {
      file = file.toAbsolutePath().normalize();
      if (line < 1) {
        throw new IllegalArgumentException("Source lines start at one");
      }
    }
  }

  /**
   * The statement that emitted an instruction. For an expanded macro, {@code caller} links to
   * the invocation that produced it, continuing outward to the original call site.
   */
  public record Origin(Location location, Origin caller) {
    public Origin {
      Objects.requireNonNull(location);
    }
  }
}
