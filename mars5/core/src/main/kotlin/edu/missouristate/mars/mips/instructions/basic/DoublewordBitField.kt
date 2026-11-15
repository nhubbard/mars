package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

/** The unsuffixed source form can select any of the three machine encodings. */
internal class DoublewordBitField(private val insert: Boolean, private val variant: String = "") : BasicInstruction(
    $$"$${if (insert) "dins" else "dext"}$$variant $t1,$t2,$${if (variant == "m") "1,40" else "32,32"}",
    if (insert) "Insert low bits into a doubleword field" else "Extract a field from a doubleword",
    R_FORMAT, bitFieldMask(insert, variant),
    { statement, context ->
        val (destination, source, position, size) = statement.operands
        val registers = context.registerFile
        val value = registers.getLongValue(source)
        val mask = -1L ushr (64 - size)
        val result = if (insert) {
            (registers.getLongValue(destination) and (mask shl position).inv()) or ((value and mask) shl position)
        } else {
            (value ushr position) and mask
        }
        context.updateLongRegister(destination, result)
    },
) {
    override fun encodeOperands(values: IntArray): IntArray {
        val position = values[2]
        val size = values[3]
        require(position in 0..63) { "$name position must be from 0 through 63" }
        require(size in 1..(64 - position)) { "$name size must be from 1 through ${64 - position} at bit $position" }
        val selected = selectVariant(position, size)
        require(variant.isEmpty() || variant == selected) { "$name cannot encode this field. Use ${name.dropLast(1)}." }
        return values.copyOf().also {
            it[2] = position and 31
            it[3] = ((if (insert) position + size else size) - 1) and 31
        }
    }

    override fun encodingMask(values: IntArray): String = bitFieldMask(insert, selectVariant(values[2], values[3]))

    override fun decodeOperands(values: IntArray) {
        val position = values[2] + if (variant == "u") 32 else 0
        val end = values[3] + 1 + if (variant == "m" || insert && variant == "u") 32 else 0
        values[2] = position
        values[3] = if (insert) end - position else end
    }

    override fun acceptsEncoding(word: Int): Boolean {
        val position = (word ushr 6) and 31
        val end = (word ushr 11) and 31
        return when {
            insert && variant == "m" -> true
            insert -> position <= end
            variant.isNotEmpty() -> position + end < 32
            else -> true
        }
    }

    private fun selectVariant(position: Int, size: Int): String = when {
        position >= 32 -> "u"
        (if (insert) position + size else size) > 32 -> "m"
        else -> ""
    }
}

private fun bitFieldMask(insert: Boolean, variant: String): String {
    val function = when (variant) {
        "m" -> 1
        "u" -> 2
        else -> 3
    } + if (insert) 4 else 0
    return "011111sssssfffffuuuuuttttt${function.toString(2).padStart(6, '0')}"
}
