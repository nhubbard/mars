package edu.missouristate.mars.api;

import edu.missouristate.mars.api.InstructionSourceMap.Location;
import java.util.List;

/**
 * An assembled, ready-to-execute MIPS program. Opaque handle returned by {@link MarsAssembler} and
 * passed to {@link MarsSimulator}. Implementations carry the machine-code list and source map.
 */
public interface Program {

  /** Returns the number of assembled instructions in the text segment. */
  int instructionCount();

  /** Returns source locations for the original assembled image. */
  default InstructionSourceMap sourceMap() {
    return InstructionSourceMap.EMPTY;
  }

  /** Returns original instructions emitted at, or through a macro call on, the given line. */
  default List<AssembledInstruction> instructionsAt(Location location, Runnable checkCancellation) {
    return List.of();
  }

  /** Returns the original basic instructions emitted by pseudo-instructions. */
  default List<AssembledInstruction> pseudoInstructions(Runnable checkCancellation) {
    return List.of();
  }

  /** Returns the definitions for the original assembled image. */
  default AssemblySymbolMap symbolMap() {
    return AssemblySymbolMap.EMPTY;
  }
}
