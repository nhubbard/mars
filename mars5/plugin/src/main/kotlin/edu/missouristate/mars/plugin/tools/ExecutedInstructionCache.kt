package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.InstructionSet

/** Caches tool metadata by the exact executed word, including self-modified instructions. */
internal class ExecutedInstructionCache(private val catalog: InstructionSet) {
    data class DecodedWord(val word: Int, val instruction: BasicInstruction?, val branchTargetOffset: Long?)

    private val entries = arrayOfNulls<DecodedWord>(1024)

    // Used by one simulation worker. Fold the opcode and operand fields into the slot index.
    fun decode(word: Int): DecodedWord {
        val index = (word xor (word ushr 10) xor (word ushr 20) xor (word ushr 30)) and (entries.size - 1)
        val cached = entries[index]
        if (cached != null && cached.word == word) return cached
        val instruction = catalog.findByBinaryCode(word)
        val displacement = if (instruction?.instructionFormat == I_BRANCH_FORMAT) {
            ProgramStatement(word, 0L, catalog).relativeBranchDestination()
        } else null
        return DecodedWord(word, instruction, displacement).also { entries[index] = it }
    }
}
