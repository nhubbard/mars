package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.SimulationCode

/** PC-relative forms with a signed 19-bit displacement encoded in words and written in bytes. */
abstract class PcRelativeWordInstruction(
    example: String, description: String, mask: String, simulate: SimulationCode,
) : BasicInstruction(example, description, I_FORMAT, mask, simulate) {
    override fun encodeOperands(values: IntArray): IntArray {
        val bytes = values[1]
        require(bytes in -0x10_0000..0x0f_fffc && bytes % Int.SIZE_BYTES == 0) {
            "$name displacement must be a multiple of four in the range -1048576 through 1048572 bytes"
        }
        return values.copyOf().also { it[1] = bytes shr 2 }
    }

    override fun decodeOperands(values: IntArray) {
        values[1] = ((values[1] shl 13) shr 13) shl 2
    }
}
