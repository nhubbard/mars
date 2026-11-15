package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrExt : BasicInstruction(
    $$"ext $t1,$t2,0,32",
    "Extract a bit field into the low bits of a register",
    R_FORMAT,
    "011111 sssss fffff uuuuu ttttt 000000",
    { statement, context ->
        val (destination, source, position, size) = statement.operands
        val mask = -1 ushr (Int.SIZE_BITS - size)
        context.updateRegister(destination, (context.registerFile.getValue(source) ushr position) and mask)
    },
) {
    override fun encodeOperands(values: IntArray): IntArray {
        requireBitField(name, values[2], values[3])
        return values.copyOf().also { it[3]-- }
    }

    override fun decodeOperands(values: IntArray) {
        values[3]++
    }

    override fun acceptsEncoding(word: Int): Boolean =
        ((word ushr 6) and 31) + ((word ushr 11) and 31) < Int.SIZE_BITS
}

internal fun requireBitField(name: String, position: Int, size: Int) {
    require(size in 1..(Int.SIZE_BITS - position)) { "$name size must be from 1 through ${Int.SIZE_BITS - position} at bit $position" }
}
