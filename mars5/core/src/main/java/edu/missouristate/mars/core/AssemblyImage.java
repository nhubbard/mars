package edu.missouristate.mars.core;

import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.api.AssemblySymbolMap;
import edu.missouristate.mars.api.InstructionSourceMap;
import edu.missouristate.mars.mips.hardware.MemoryState.DataImage;
import java.util.List;

/** Detached instructions, initialized memory, and source metadata from one successful assembly. */
public final class AssemblyImage {
  final String filename;
  final String layout;
  final int entry;
  final List<ProgramStatement.Image> instructions;
  final DataImage data;
  final InstructionSourceMap sources;
  final AssemblySymbolMap symbols;

  AssemblyImage(
      String filename, String layout, int entry, List<ProgramStatement.Image> instructions,
      DataImage data, InstructionSourceMap sources, AssemblySymbolMap symbols) {
    this.filename = filename;
    this.layout = layout;
    this.entry = entry;
    this.instructions = List.copyOf(instructions);
    this.data = data;
    this.sources = sources;
    this.symbols = symbols;
  }

  /** Compares the executable output while allowing changes to source names and positions. */
  public boolean hasSameMachineImage(AssemblyImage other, Runnable checkCancellation) {
    if (entry != other.entry || !layout.equals(other.layout) || instructions.size() != other.instructions.size()) {
      return false;
    }
    for (int i = 0; i < instructions.size(); i++) {
      checkCancellation.run();
      if (!instructions.get(i).hasSameMachineWord(other.instructions.get(i))) {
        return false;
      }
    }
    return data.hasSameContents(other.data, checkCancellation);
  }
}
