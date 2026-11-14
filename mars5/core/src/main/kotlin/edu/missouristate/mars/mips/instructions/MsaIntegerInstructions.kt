package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import kotlin.math.abs

internal fun msaIntegerInstructions(): List<BasicInstruction> =
    "bhwd".flatMapIndexed { format, suffix ->
        val bits = Byte.SIZE_BITS shl format
        val formatField = format.toString(2).padStart(2, '0')
        val shifts = ShiftOperation.entries.flatMap { operation ->
            listOf(shiftInstruction(operation, format, suffix, immediate = false), shiftInstruction(operation, format, suffix, immediate = true))
        }
        IntegerOperation.entries.map { integerInstruction(it, format, suffix) } +
            WideArithmetic.entries.map { wideArithmetic(it, format, suffix) } +
            immediateOperations.map { immediateInstruction(it.first, it.second, format, suffix) } + shifts + listOf(
            countInstruction("pcnt", "001", formatField, suffix, bits) { it.countOneBits() },
            countInstruction("nlzc", "011", formatField, suffix, bits) { it.countLeadingZeroBits() - (64 - bits) },
            countInstruction("nloc", "010", formatField, suffix, bits) {
                (it xor (-1L ushr (64 - bits))).countLeadingZeroBits() - (64 - bits)
            },
        )
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

private enum class WideArithmetic(val mnemonic: String, val opcode: Int, val function: Int, val signed: Boolean) {
    ADD_ABSOLUTE("add_a", 0, 16, true),
    ADD_SATURATING_ABSOLUTE("adds_a", 1, 16, true),
    ADD_SATURATING_SIGNED("adds_s", 2, 16, true),
    ADD_SATURATING_UNSIGNED("adds_u", 3, 16, false),
    AVERAGE_SIGNED("ave_s", 4, 16, true),
    AVERAGE_UNSIGNED("ave_u", 5, 16, false),
    AVERAGE_ROUNDED_SIGNED("aver_s", 6, 16, true),
    AVERAGE_ROUNDED_UNSIGNED("aver_u", 7, 16, false),
    SUBTRACT_SATURATING_SIGNED("subs_s", 0, 17, true),
    SUBTRACT_SATURATING_UNSIGNED("subs_u", 1, 17, false),
    ABSOLUTE_DIFFERENCE_SIGNED("asub_s", 4, 17, true),
    ABSOLUTE_DIFFERENCE_UNSIGNED("asub_u", 5, 17, false);

    fun apply(a: Long, b: Long, bits: Int): Long {
        val maxSigned = Long.MAX_VALUE ushr (64 - bits)
        val minSigned = -maxSigned - 1
        val maxUnsigned = -1L ushr (64 - bits)
        val xor = a xor b
        return when (this) {
            ADD_ABSOLUTE -> abs(a) + abs(b)
            ADD_SATURATING_ABSOLUTE -> {
                val left = abs(a)
                val right = abs(b)
                if (left < 0 || right < 0 || right > maxSigned || left > maxSigned - right) maxSigned
                else left + right
            }
            ADD_SATURATING_SIGNED -> {
                val sum = a + b
                if (((a xor sum) and (b xor sum)) < 0) {
                    if (a < 0) minSigned else maxSigned
                } else sum.coerceIn(minSigned, maxSigned)
            }
            ADD_SATURATING_UNSIGNED ->
                if (a.toULong() > (maxUnsigned - b).toULong()) maxUnsigned else a + b
            AVERAGE_SIGNED -> (a and b) + (xor shr 1)
            AVERAGE_UNSIGNED -> (a and b) + (xor ushr 1)
            AVERAGE_ROUNDED_SIGNED -> (a and b) + (xor shr 1) + (xor and 1)
            AVERAGE_ROUNDED_UNSIGNED -> (a and b) + (xor ushr 1) + (xor and 1)
            SUBTRACT_SATURATING_SIGNED -> {
                val difference = a - b
                if ((xor and (a xor difference)) < 0) {
                    if (a < 0) minSigned else maxSigned
                } else difference.coerceIn(minSigned, maxSigned)
            }
            SUBTRACT_SATURATING_UNSIGNED -> if (a.toULong() < b.toULong()) 0L else a - b
            ABSOLUTE_DIFFERENCE_SIGNED -> if (a < b) b - a else a - b
            ABSOLUTE_DIFFERENCE_UNSIGNED -> if (a.toULong() < b.toULong()) b - a else a - b
        }
    }
}

private fun wideArithmetic(operation: WideArithmetic, format: Int, suffix: Char): BasicInstruction {
    val field = (operation.opcode * 4 + format).toString(2).padStart(5, '0')
    val function = operation.function.toString(2).padStart(6, '0')
    val bits = Byte.SIZE_BITS shl format
    return BasicInstruction(
        "${operation.mnemonic}.$suffix \$w1,\$w2,\$w3", "Apply ${operation.mnemonic} to each integer vector element",
        R_FORMAT, "011110 $field ttttt sssss fffff $function",
    ) { statement, context ->
        val (destination, left, right) = statement.operands
        val fpu = context.coprocessor1
        val a = fpu.readVector(left)
        val b = fpu.readVector(right)
        context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
            operation.apply(element(half(a), shift, bits, operation.signed), element(half(b), shift, bits, operation.signed), bits)
        })
    }
}

private val immediateOperations = listOf(
    "addvi" to IntegerOperation.ADD, "subvi" to IntegerOperation.SUBTRACT,
    "maxi_s" to IntegerOperation.MAX_SIGNED, "maxi_u" to IntegerOperation.MAX_UNSIGNED,
    "mini_s" to IntegerOperation.MIN_SIGNED, "mini_u" to IntegerOperation.MIN_UNSIGNED,
    "ceqi" to IntegerOperation.EQUAL,
    "clti_s" to IntegerOperation.LESS_SIGNED, "clti_u" to IntegerOperation.LESS_UNSIGNED,
    "clei_s" to IntegerOperation.LESS_EQUAL_SIGNED, "clei_u" to IntegerOperation.LESS_EQUAL_UNSIGNED,
)

private fun immediateInstruction(name: String, operation: IntegerOperation, format: Int, suffix: Char): BasicInstruction {
    val signed = operation.signed || operation == IntegerOperation.EQUAL
    val range = if (signed) -16..15 else 0..31
    val opcode = ((operation.opcode shl 2) or format).toString(2).padStart(5, '0')
    val function = if (operation.function == 14) "000110" else "000111"
    val bits = Byte.SIZE_BITS shl format
    return object : BasicInstruction(
        "$name.$suffix \$w1,\$w2,${if (signed) -16 else 31}", "Apply $name with a constant to each vector element",
        R_FORMAT, "011110 $opcode ttttt sssss fffff $function",
        { statement, context ->
            val (destination, source, immediate) = statement.operands
            val input = context.coprocessor1.readVector(source)
            context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
                operation.apply(element(half(input), shift, bits, signed), immediate.toLong(), 0L)
            })
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(values[2] in range) { "$name requires an immediate between ${range.first} and ${range.last}" }
            return values
        }

        override fun decodeOperands(values: IntArray) {
            if (signed) values[2] = (values[2] shl 27) shr 27
        }
    }
}

private fun truthMask(result: Boolean): Long = if (result) -1L else 0L

private fun integerInstruction(operation: IntegerOperation, format: Int, suffix: Char): BasicInstruction {
    val opcode = ((operation.opcode shl 2) or format).toString(2).padStart(5, '0')
    val function = operation.function.toString(2).padStart(6, '0')
    val bits = Byte.SIZE_BITS shl format
    return BasicInstruction(
        "${operation.mnemonic}.$suffix \$w1,\$w2,\$w3", "Apply ${operation.mnemonic} to each integer vector element",
        R_FORMAT, "011110 $opcode ttttt sssss fffff $function",
    ) { statement, context ->
        val (destination, left, right) = statement.operands
        val registers = context.coprocessor1
        val a = registers.readVector(left)
        val b = registers.readVector(right)
        val previous = if (operation.accumulate) registers.readVector(destination) else VectorRegister(0L, 0L)
        context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
            operation.apply(
                element(half(a), shift, bits, operation.signed),
                element(half(b), shift, bits, operation.signed),
                element(half(previous), shift, bits, operation.signed),
            )
        })
    }
}

private inline fun mapLanes(bits: Int, transform: (half: (VectorRegister) -> Long, shift: Int) -> Long): VectorRegister {
    val mask = -1L ushr (Long.SIZE_BITS - bits)
    var low = 0L
    var high = 0L
    for (shift in 0 until Long.SIZE_BITS step bits) {
        low = low or ((transform(VectorRegister::low, shift) and mask) shl shift)
        high = high or ((transform(VectorRegister::high, shift) and mask) shl shift)
    }
    return VectorRegister(low, high)
}

private fun element(half: Long, shift: Int, bits: Int, signed: Boolean = false): Long {
    val extension = Long.SIZE_BITS - bits
    val lane = (half ushr shift) shl extension
    return if (signed) lane shr extension else lane ushr extension
}

private enum class ShiftOperation(
    val mnemonic: String, val immediateMnemonic: String, val opcode: String, val function: String,
    val immediateFunction: String, val signed: Boolean = false, val round: Boolean = false,
) {
    LEFT("sll", "slli", "000", "001101", "001001"),
    RIGHT_ARITHMETIC("sra", "srai", "001", "001101", "001001", signed = true),
    RIGHT_LOGICAL("srl", "srli", "010", "001101", "001001"),
    RIGHT_ARITHMETIC_ROUNDED("srar", "srari", "001", "010101", "001010", signed = true, round = true),
    RIGHT_LOGICAL_ROUNDED("srlr", "srlri", "010", "010101", "001010", round = true),
}

private fun shiftInstruction(operation: ShiftOperation, format: Int, suffix: Char, immediate: Boolean): BasicInstruction {
    val bits = Byte.SIZE_BITS shl format
    val name = if (immediate) operation.immediateMnemonic else operation.mnemonic
    val shiftOpcode = when {
        immediate && operation == ShiftOperation.RIGHT_ARITHMETIC_ROUNDED -> "010"
        immediate && operation == ShiftOperation.RIGHT_LOGICAL_ROUNDED -> "011"
        else -> operation.opcode
    }
    val selector = if (immediate) listOf("1110ttt", "110tttt", "10ttttt", "0tttttt")[format]
    else format.toString(2).padStart(2, '0') + "ttttt"
    val function = if (immediate) operation.immediateFunction else operation.function
    val operand = if (immediate) (bits - 1).toString() else "\$w3"
    return object : BasicInstruction(
        "$name.$suffix \$w1,\$w2,$operand", "Shift each vector element independently",
        R_FORMAT, "011110 $shiftOpcode$selector sssss fffff $function",
        { statement, context ->
            val (destination, source, count) = statement.operands
            val registers = context.coprocessor1
            val value = registers.readVector(source)
            val counts = if (immediate) null else registers.readVector(count)
            context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
                val input = element(half(value), shift, bits, operation.signed)
                val distance = if (counts == null) count else element(half(counts), shift, bits).toInt() and (bits - 1)
                val shifted = when {
                    operation == ShiftOperation.LEFT -> input shl distance
                    operation.signed -> input shr distance
                    else -> input ushr distance
                }
                shifted + if (operation.round && distance != 0) ((input ushr (distance - 1)) and 1) else 0L
            })
        },
    ) {
        override fun encodeOperands(values: IntArray): IntArray {
            require(!immediate || values[2] in 0 until bits) { "Vector shift count must be between 0 and ${bits - 1}" }
            return values
        }
    }
}

private fun countInstruction(name: String, opcode: String, format: String, suffix: Char, bits: Int, count: (Long) -> Int) =
    BasicInstruction(
        "$name.$suffix \$w1,\$w2", "Count bits in each vector element",
        R_FORMAT, "011110 11000 $opcode$format sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val value = context.coprocessor1.readVector(source)
        context.updateVectorRegister(destination, mapLanes(bits) { half, shift -> count(element(half(value), shift, bits)).toLong() })
    }
