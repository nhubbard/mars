package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.SimulationCode

abstract class CompactBranchInstruction(
    example: String, description: String, mask: String, simulate: SimulationCode,
) : BasicInstruction(example, description, I_BRANCH_FORMAT, mask, simulate) {
    override fun encodeOperands(values: IntArray): IntArray {
        require(values[0] in -0x200_0000..0x1ff_ffff) { "Compact branch destination is outside its signed displacement range" }
        return values
    }

    override fun decodeOperands(values: IntArray) {
        values[0] = (values[0] shl 6) shr 6
    }
}
