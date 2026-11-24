package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction

/** The register-trap code is available to exception handlers in the instruction word. */
internal class InstrCodedTrap(base: BasicInstruction) :
    BasicInstruction(
        "${base.exampleFormat},100",
        base.description,
        base.instructionFormat,
        base.operationMask.replace('c', 't'),
        base.simulationCode,
    ) {
  // Preserve the unadorned two-register spelling when decoding code zero.
  override fun acceptsEncoding(word: Int): Boolean = (word ushr 6) and MAX_CODE != 0

  companion object {
    const val MAX_CODE = 1023
  }
}
