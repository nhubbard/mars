package edu.missouristate.mars.api;

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

  /** Returns the definitions for the original assembled image. */
  default AssemblySymbolMap symbolMap() {
    return AssemblySymbolMap.EMPTY;
  }
}
