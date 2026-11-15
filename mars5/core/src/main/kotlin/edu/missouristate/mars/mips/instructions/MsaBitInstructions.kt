package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

internal fun msaBitInstructions(): List<BasicInstruction> = buildList {
    for ((format, suffix) in "bhwd".withIndex()) {
        for (operation in LaneBitOperation.entries) {
            add(laneBitInstruction(operation, format, suffix, immediate = false))
            add(laneBitInstruction(operation, format, suffix, immediate = true))
        }
    }
    for (operation in MaskOperation.entries) {
        add(maskInstruction(operation, immediate = true))
        if (operation.vectorOpcode != null) add(maskInstruction(operation, immediate = false))
    }
}

private enum class LaneBitOperation(val mnemonic: String, val opcode: Int, val merges: Boolean = false) {
    CLEAR("bclr", 3),
    SET("bset", 4),
    TOGGLE("bneg", 5),
    INSERT_LEFT("binsl", 6, merges = true),
    INSERT_RIGHT("binsr", 7, merges = true);

    fun apply(source: Long, previous: Long, position: Int, bits: Int): Long {
        val bit = 1L shl position
        return when (this) {
            CLEAR -> source and bit.inv()
            SET -> source or bit
            TOGGLE -> source xor bit
            INSERT_LEFT -> merge(previous, source, -1L shl (bits - position - 1))
            INSERT_RIGHT -> merge(previous, source, -1L ushr (63 - position))
        }
    }
}

private fun laneBitInstruction(operation: LaneBitOperation, format: Int, suffix: Char, immediate: Boolean): BasicInstruction {
    val bits = Byte.SIZE_BITS shl format
    val opcode = operation.opcode.toString(2).padStart(3, '0')
    val selector = if (immediate) msaBitImmediateField(format) else format.toString(2).padStart(2, '0') + "ttttt"
    val function = if (immediate) "001001" else "001101"
    val name = operation.mnemonic + if (immediate) "i" else ""
    val operand = if (immediate) (bits - 1).toString() else $$"$w3"
    return object : BasicInstruction(
        $$"$$name.$$suffix $w1,$w2,$$operand", "Modify selected bits in each vector element",
        R_FORMAT, "011110 $opcode$selector sssss fffff $function",
        { statement, context ->
            val (destination, source, index) = statement.operands
            val fpu = context.coprocessor1
            val input = fpu.readVector(source)
            val positions = if (immediate) null else fpu.readVector(index)
            val previous = if (operation.merges) fpu.readVector(destination) else VectorRegister(0L, 0L)
            context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
                val position = if (positions == null) index else element(half(positions), shift, bits).toInt() and (bits - 1)
                operation.apply(element(half(input), shift, bits), element(half(previous), shift, bits), position, bits)
            })
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(!immediate || values[2] in 0 until bits) { "Vector bit position must be between 0 and ${bits - 1}" }
            return values
        }
    }
}

private fun merge(previous: Long, source: Long, mask: Long) = (previous and mask.inv()) or (source and mask)

private enum class MaskOperation(
    val mnemonic: String, val immediateOpcode: String, val vectorOpcode: String? = null,
    val apply: (Long, Long, Long) -> Long,
) {
    AND("andi", "00", apply = { source, mask, _ -> source and mask }),
    OR("ori", "01", apply = { source, mask, _ -> source or mask }),
    NOR("nori", "10", apply = { source, mask, _ -> (source or mask).inv() }),
    XOR("xori", "11", apply = { source, mask, _ -> source xor mask }),
    MOVE_IF_SET("bmnz", "00", "00100", { source, mask, previous -> merge(previous, source, mask) }),
    MOVE_IF_CLEAR("bmz", "01", "00101", { source, mask, previous -> merge(source, previous, mask) }),
    SELECT("bsel", "10", "00110", { source, target, previous -> merge(source, target, previous) }),
}

private fun maskInstruction(operation: MaskOperation, immediate: Boolean): BasicInstruction {
    val merge = operation.vectorOpcode != null
    val name = operation.mnemonic + if (immediate && merge) "i" else ""
    val tail = if (immediate) {
        "${operation.immediateOpcode} tttttttt sssss fffff ${if (merge) "000001" else "000000"}"
    } else "${operation.vectorOpcode} ttttt sssss fffff 011110"
    val example = if (immediate) $$"$$name.b $w1,$w2,255" else $$"$$name.v $w1,$w2,$w3"
    return object : BasicInstruction(
        example, "Apply a bit mask to a vector",
        R_FORMAT, "011110 $tail",
        { statement, context ->
            val (destination, source, operand) = statement.operands
            val fpu = context.coprocessor1
            val input = fpu.readVector(source)
            val mask = if (immediate) {
                val repeated = operand * 0x0101010101010101L
                VectorRegister(repeated, repeated)
            } else fpu.readVector(operand)
            val previous = if (merge) fpu.readVector(destination) else VectorRegister(0L, 0L)
            context.updateVectorRegister(destination, VectorRegister(
                operation.apply(input.low, mask.low, previous.low),
                operation.apply(input.high, mask.high, previous.high),
            ))
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(!immediate || values[2] in 0..255) { "Vector mask must be between 0 and 255" }
            return values
        }
    }
}
