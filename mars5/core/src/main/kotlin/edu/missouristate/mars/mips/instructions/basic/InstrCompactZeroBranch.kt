package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT

internal class InstrCompactZeroBranch(branchOnZero: Boolean) : BasicInstruction(
    $$"$${if (branchOnZero) "beqzc" else "bnezc"} $t1,label",
    if (branchOnZero) "Branch immediately if the register is zero" else "Branch immediately if the register is nonzero",
    I_BRANCH_FORMAT,
    "${if (branchOnZero) "110110" else "111110"} fffff sssssssssssssssssssss",
    { statement, context ->
        val (source, displacement) = statement.operands
        context.checkCompactTransferSlot(statement)
        if ((context.readIntegerOperand(source) == 0L) == branchOnZero) {
            val target = context.registerFile.programCounterBits + (displacement shl 2)
            context.compactTransfer(statement, target, branch = true)
        }
    },
) {
    override val isCompactConditionalBranch: Boolean get() = true

    override fun encodeOperands(values: IntArray): IntArray {
        require(values[0] != 0) { "Compact zero branch requires a nonzero register number" }
        require(values[1] in -0x100000..0xfffff) { "Compact branch destination is outside its signed displacement range" }
        return values
    }

    override fun decodeOperands(values: IntArray) {
        values[1] = (values[1] shl 11) shr 11
    }

    // A zero in the register field selects JIC or JIALC under the same primary opcode.
    override fun acceptsEncoding(word: Int): Boolean = (word ushr 21) and 31 != 0
}
