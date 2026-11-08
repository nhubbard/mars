package edu.missouristate.mars.mips.instructions;

/** Read-only instruction metadata for tokenizing standalone examples and disassembly. */
public final class InstructionDefaults {
  private InstructionDefaults() {}

  private static class Holder {
    static final InstructionSet SET = create();
  }

  private static InstructionSet create() {
    var set = new InstructionSet();
    set.populate();
    return set;
  }

  public static InstructionSet get() {
    return Holder.SET;
  }
}
