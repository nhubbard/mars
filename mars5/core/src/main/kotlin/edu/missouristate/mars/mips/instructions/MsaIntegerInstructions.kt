package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

internal fun msaIntegerInstructions(): List<BasicInstruction> =
    IntegerOperation.entries.flatMap { operation ->
        "bhwd".mapIndexed { format, suffix -> integerInstruction(operation, format, suffix) }
    }

private enum class IntegerOperation(
    val mnemonic: String,
    val opcode: Int,
    val function: Int,
    val signed: Boolean = false,
    val accumulate: Boolean = false,
    val apply: (Long, Long, Long) -> Long,
) {
    ADD("addv", 0, 14, apply = { a, b, _ -> a + b }),
    SUBTRACT("subv", 1, 14, apply = { a, b, _ -> a - b }),
    MULTIPLY("mulv", 0, 18, apply = { a, b, _ -> a * b }),
    MULTIPLY_ADD("maddv", 1, 18, accumulate = true, apply = { a, b, d -> d + a * b }),
    MULTIPLY_SUBTRACT("msubv", 2, 18, accumulate = true, apply = { a, b, d -> d - a * b }),
    MAX_SIGNED("max_s", 2, 14, signed = true, apply = { a, b, _ -> maxOf(a, b) }),
    MAX_UNSIGNED("max_u", 3, 14, apply = { a, b, _ -> maxOf(a.toULong(), b.toULong()).toLong() }),
    MIN_SIGNED("min_s", 4, 14, signed = true, apply = { a, b, _ -> minOf(a, b) }),
    MIN_UNSIGNED("min_u", 5, 14, apply = { a, b, _ -> minOf(a.toULong(), b.toULong()).toLong() }),
    EQUAL("ceq", 0, 15, apply = { a, b, _ -> truthMask(a == b) }),
    LESS_SIGNED("clt_s", 2, 15, signed = true, apply = { a, b, _ -> truthMask(a < b) }),
    LESS_UNSIGNED("clt_u", 3, 15, apply = { a, b, _ -> truthMask(a.toULong() < b.toULong()) }),
    LESS_EQUAL_SIGNED("cle_s", 4, 15, signed = true, apply = { a, b, _ -> truthMask(a <= b) }),
    LESS_EQUAL_UNSIGNED("cle_u", 5, 15, apply = { a, b, _ -> truthMask(a.toULong() <= b.toULong()) }),
}

private fun truthMask(result: Boolean): Long = if (result) -1L else 0L

private fun integerInstruction(operation: IntegerOperation, format: Int, suffix: Char): BasicInstruction {
    val opcode = ((operation.opcode shl 2) or format).toString(2).padStart(5, '0')
    val function = operation.function.toString(2).padStart(6, '0')
    val bits = Byte.SIZE_BITS shl format
    val extension = Long.SIZE_BITS - bits
    val mask = -1L ushr extension

    fun element(half: Long, shift: Int): Long {
        val lane = (half ushr shift) and mask
        return if (operation.signed) (lane shl extension) shr extension else lane
    }

    fun applyHalf(left: Long, right: Long, previous: Long): Long {
        var result = 0L
        for (shift in 0 until Long.SIZE_BITS step bits) {
            val value = operation.apply(element(left, shift), element(right, shift), element(previous, shift))
            result = result or ((value and mask) shl shift)
        }
        return result
    }

    return BasicInstruction(
        "${operation.mnemonic}.$suffix \$w1,\$w2,\$w3", "Apply ${operation.mnemonic} to each integer vector element",
        R_FORMAT, "011110 $opcode ttttt sssss fffff $function",
    ) { statement, context ->
        val (destination, left, right) = statement.operands
        val registers = context.coprocessor1
        val a = registers.readVector(left)
        val b = registers.readVector(right)
        val previous = if (operation.accumulate) registers.readVector(destination) else VectorRegister(0L, 0L)
        context.updateVectorRegister(destination, VectorRegister(
            applyHalf(a.low, b.low, previous.low),
            applyHalf(a.high, b.high, previous.high),
        ))
    }
}
