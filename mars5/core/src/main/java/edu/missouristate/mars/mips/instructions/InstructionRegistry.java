package edu.missouristate.mars.mips.instructions;

import java.util.List;

/** Compatibility name for the data-driven instruction loader. */
public final class InstructionRegistry {
  private InstructionRegistry() {}

  public static List<BasicInstruction> getAll() {
    return new InstructionLoader().loadBasicInstructions();
  }
}
