package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrIns : BasicInstruction(
    "ins \$t1,\$t2,0,32",
    "Replace a bit field with the low bits from another register",
    R_FORMAT,
    "011111 sssss fffff uuuuu ttttt 000100",
    { statement, context ->
        val (destination, source, position, size) = statement.operands
        val mask = (-1 ushr (Int.SIZE_BITS - size)) shl position
        val registers = context.registerFile
        val retained = registers.getValue(destination) and mask.inv()
        val inserted = (registers.getValue(source) shl position) and mask
        context.updateRegister(destination, retained or inserted)
    },
) {
    override fun encodeOperands(values: IntArray): IntArray {
        requireBitField(name, values[2], values[3])
        return values.copyOf().also { it[3] += it[2] - 1 }
    }

    override fun decodeOperands(values: IntArray) {
        values[3] -= values[2] - 1
    }

    override fun acceptsEncoding(word: Int): Boolean =
        ((word ushr 6) and 31) <= ((word ushr 11) and 31)
}
