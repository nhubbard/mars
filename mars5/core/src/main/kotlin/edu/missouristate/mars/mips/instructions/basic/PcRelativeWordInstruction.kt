package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.SimulationCode

/** PC-relative forms with a signed displacement written in bytes. */
abstract class PcRelativeWordInstruction @JvmOverloads constructor(
    example: String, description: String, mask: String,
    simulate: SimulationCode,
    private val displacementShift: Int = 2,
) : BasicInstruction(example, description, I_FORMAT, mask, simulate) {
    override fun encodeOperands(values: IntArray): IntArray {
        val bytes = values[1]
        val alignment = 1 shl displacementShift
        val alignmentName = if (alignment == 4) "four" else "eight"
        val limit = 0x10_0000
        require(bytes in -limit..(limit - alignment) && bytes % alignment == 0) {
            "$name displacement must be a multiple of $alignmentName in the range ${-limit} through ${limit - alignment} bytes"
        }
        return values.copyOf().also { it[1] = bytes shr displacementShift }
    }

    override fun decodeOperands(values: IntArray) {
        val signShift = 11 + displacementShift
        values[1] = (values[1] shl signShift) shr 11
    }
}
