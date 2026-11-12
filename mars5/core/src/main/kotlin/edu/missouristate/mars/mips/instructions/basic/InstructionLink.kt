package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.instructions.Instruction.INSTRUCTION_LENGTH

/** Saves the address following the jump or its delay slot, as selected by the simulation. */
internal fun InstructionContext.linkReturnAddress(register: Int) {
    val nextAddress = registerFile.getProgramCounter()
    val delay = if (settings().isDelayedBranchingEnabled) INSTRUCTION_LENGTH else 0
    link(register, nextAddress + delay)
}
