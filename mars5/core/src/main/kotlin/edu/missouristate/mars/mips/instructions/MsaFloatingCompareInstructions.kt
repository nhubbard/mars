package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.CAUSE_MASK
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.FLUSH_SUBNORMALS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INEXACT
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.MSA_NON_TRAPPING
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.OVERFLOW
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.UNDERFLOW
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.UNDERFLOW_ENABLE
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.basic.convertInteger
import edu.missouristate.mars.mips.instructions.basic.floatingClass
import edu.missouristate.mars.mips.instructions.basic.narrowDouble
import edu.missouristate.mars.mips.instructions.basic.narrowSingleToHalf
import edu.missouristate.mars.mips.instructions.basic.roundIntegral
import edu.missouristate.mars.mips.instructions.basic.widenSingle
import java.lang.Double.MIN_NORMAL as DOUBLE_MIN_NORMAL
import java.lang.Float.float16ToFloat
import java.math.BigDecimal
import kotlin.math.abs

internal fun msaFloatingCompareInstructions(): List<BasicInstruction> = buildList {
    for ((format, suffix) in "hw".withIndex()) {
        add(narrowFloatingInstruction(format, suffix, fixedPoint = false))
        add(narrowFloatingInstruction(format, suffix, fixedPoint = true))
    }
    for ((format, suffix) in "wd".withIndex()) {
        val bits = 32 shl format
        add(BasicInstruction(
            "fclass.$suffix \$w1,\$w2", "Classify each floating-point element",
            R_FORMAT, "011110 11001 0000$format sssss fffff 011110",
        ) { statement, context ->
            val (destination, source) = statement.operands
            val input = context.coprocessor1.readVector(source)
            context.updateVectorRegister(destination, mapLanes(bits) { half, shift ->
                val value = element(half(input), shift, bits)
                floatingClass(value, if (bits == 32) 23 else 52, if (bits == 32) 8 else 11).toLong()
            })
        })
        add(roundingInstruction(format, suffix))
        for (operation in FloatingUnary.entries) add(unaryInstruction(operation, format, suffix))
        add(scaleInstruction(format, suffix))
        for (operation in FloatingArithmetic.entries) add(arithmeticInstruction(operation, format, suffix))
        for (signed in listOf(true, false)) {
            add(toIntegerInstruction(format, suffix, signed, truncate = false))
            add(toIntegerInstruction(format, suffix, signed, truncate = true))
            add(fromIntegerInstruction(format, suffix, signed))
        }
        add(fromFixedPointInstruction(format, suffix, left = true))
        add(fromFixedPointInstruction(format, suffix, left = false))
        add(widenFloatingInstruction(format, suffix, left = true))
        add(widenFloatingInstruction(format, suffix, left = false))
        for ((index, name) in listOf("fmin", "fmin_a", "fmax", "fmax_a").withIndex()) {
            add(extremumInstruction(name, index, format, suffix))
        }
        for (signaling in listOf(false, true)) {
            for ((opcode, name) in listOf("af", "un", "eq", "ueq", "lt", "ult", "le", "ule").withIndex()) {
                add(compare(name, opcode, format, suffix, signaling, complement = false))
            }
            for ((index, name) in listOf("or", "une", "ne").withIndex()) {
                add(compare(name, index + 1, format, suffix, signaling, complement = true))
            }
        }
    }
}

private fun compare(name: String, predicate: Int, format: Int, suffix: Char, signaling: Boolean, complement: Boolean): BasicInstruction {
    val field = ((if (signaling) 16 else 0) + predicate * 2 + format).toString(2).padStart(5, '0')
    val function = if (complement) "011100" else "011010"
    val precision = MsaPrecision(format)
    val mnemonic = "f${if (signaling) 's' else 'c'}$name.$suffix"
    return BasicInstruction(
        "$mnemonic \$w1,\$w2,\$w3", "Compare floating-point elements and write truth masks",
        R_FORMAT, "011110 $field ttttt sssss fffff $function",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val fpu = context.coprocessor1
        val a = fpu.readVector(source)
        val b = fpu.readVector(target)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val left = a.floatingLane(index, precision.bits)
            val right = b.floatingLane(index, precision.bits)
            val unordered = precision.isNan(left) || precision.isNan(right)
            val invalid = (signaling && unordered) || precision.isSignaling(left) || precision.isSignaling(right)
            val relation = if (unordered) 1 else {
                val x = precision.number(precision.flush(left, status))
                val y = precision.number(precision.flush(right, status))
                when {
                    x < y -> 4
                    x == y -> 2
                    else -> 0
                }
            }
            FloatingLane(if ((relation and predicate != 0) != complement) -1L else 0L, if (invalid) INVALID_OPERATION else 0)
        }
    }
}

private class MsaPrecision(format: Int) {
    val bits = 32 shl format
    val exponent = if (format == 0) 0x7f800000L else 0x7ff0000000000000L
    val quiet = if (format == 0) 1L shl 22 else 1L shl 51
    private val fraction = (quiet shl 1) - 1
    private val sign = 1L shl (bits - 1)

    fun isNan(raw: Long) = raw and exponent == exponent && raw and fraction != 0L
    fun isSignaling(raw: Long) = isNan(raw) && raw and quiet == 0L
    fun number(raw: Long) = if (bits == 32) Float.fromBits(raw.toInt()).toDouble() else Double.fromBits(raw)
    fun raw(value: Double) = if (bits == 32) value.toFloat().toRawBits().toUInt().toLong() else value.toRawBits()

    fun flush(raw: Long, status: Int): Long =
        if (status and FLUSH_SUBNORMALS != 0 && raw and exponent == 0L) raw and sign else raw
}

private data class FloatingLane(val bits: Long, val causes: Int = 0)

private fun InstructionContext.writeFloatingVector(
    statement: ProgramStatement, destination: Int, precision: MsaPrecision,
    evaluate: (index: Int, status: Int) -> FloatingLane,
) = writeFloatingVector(statement, destination, precision.bits, precision.exponent, evaluate)

private fun InstructionContext.writeFloatingVector(
    statement: ProgramStatement, destination: Int, bits: Int, exponent: Long,
    evaluate: (index: Int, status: Int) -> FloatingLane,
) {
    val status = coprocessor1.msaStatus
    val enabled = ((status ushr 7) and 0x1f) or 0x20
    val nonTrapping = status and MSA_NON_TRAPPING != 0
    var causes = 0
    fun evaluateLane(index: Int): Long {
        val lane = evaluate(index, status)
        return if (nonTrapping && lane.causes and enabled != 0) exponent or lane.causes.toLong()
        else {
            causes = causes or lane.causes
            lane.bits
        }
    }
    val mask = -1L ushr (64 - bits)
    fun pack(start: Int): Long = (0 until 64 step bits).fold(0L) { result, shift ->
        result or ((evaluateLane(start + shift / bits) and mask) shl shift)
    }
    val result = VectorRegister(pack(0), pack(64 / bits))
    val updated = (status and CAUSE_MASK.inv()) or (causes shl 12)
    writeMsaStatus(statement, if (causes and enabled != 0) updated else updated or (causes shl 2))
    updateVectorRegister(destination, result)
}

private fun roundingInstruction(format: Int, suffix: Char): BasicInstruction {
    val precision = MsaPrecision(format)
    return BasicInstruction(
        "frint.$suffix \$w1,\$w2", "Round each floating-point element to an integral value",
        R_FORMAT, "011110 11001 0110$format sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val input = context.coprocessor1.readVector(source)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val raw = input.floatingLane(index, precision.bits)
            if (precision.isNan(raw)) {
                FloatingLane(raw or precision.quiet, if (precision.isSignaling(raw)) INVALID_OPERATION else 0)
            } else {
                val rounded = roundIntegral(precision.number(precision.flush(raw, status)), status and 3)
                FloatingLane(precision.raw(rounded), if (rounded != precision.number(raw)) INEXACT else 0)
            }
        }
    }
}

private fun extremumInstruction(name: String, index: Int, format: Int, suffix: Char): BasicInstruction {
    val precision = MsaPrecision(format)
    val field = (24 + index * 2 + format).toString(2)
    val minimum = index < 2
    val magnitude = index and 1 != 0
    return BasicInstruction(
        "$name.$suffix \$w1,\$w2,\$w3", "Select an extremum from corresponding floating-point elements",
        R_FORMAT, "011110 $field ttttt sssss fffff 011011",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val fpu = context.coprocessor1
        val left = fpu.readVector(source)
        val right = fpu.readVector(target)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val rawA = left.floatingLane(index, precision.bits)
            val rawB = right.floatingLane(index, precision.bits)
            val a = precision.flush(rawA, status)
            val b = precision.flush(rawB, status)
            val inexact = if (a != rawA || b != rawB) INEXACT else 0
            when {
                precision.isSignaling(a) -> FloatingLane(a or precision.quiet, INVALID_OPERATION or inexact)
                precision.isSignaling(b) -> FloatingLane(b or precision.quiet, INVALID_OPERATION or inexact)
                precision.isNan(b) -> FloatingLane(a, inexact)
                precision.isNan(a) -> FloatingLane(b, inexact)
                else -> {
                    val x = precision.number(a)
                    val y = precision.number(b)
                    val comparison = if (magnitude) abs(x).compareTo(abs(y)) else 0
                    val order = if (comparison == 0) x.compareTo(y) else comparison
                    FloatingLane(if (if (minimum) order <= 0 else order >= 0) a else b, inexact)
                }
            }
        }
    }
}

private fun toIntegerInstruction(format: Int, suffix: Char, signed: Boolean, truncate: Boolean): BasicInstruction {
    val precision = MsaPrecision(format)
    val mnemonic = (if (truncate) "ftrunc" else "ftint") + if (signed) "_s" else "_u"
    val opcode = (if (truncate) 2 else 24) + (if (signed) 0 else 2) + format
    val field = opcode.toString(2).padStart(5, '0')
    val limit = Math.scalb(1.0, precision.bits - if (signed) 1 else 0)
    val lowerBound = if (signed) -limit else 0.0
    val minimum = if (signed) -1L shl (precision.bits - 1) else 0L
    val maximum = -1L ushr (64 - precision.bits + if (signed) 1 else 0)
    return BasicInstruction(
        "$mnemonic.$suffix \$w1,\$w2", "Convert each floating-point element to an integer",
        R_FORMAT, "011110 11001 $field sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val input = context.coprocessor1.readVector(source)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val raw = input.floatingLane(index, precision.bits)
            val value = precision.number(raw)
            val rounded = roundIntegral(precision.number(precision.flush(raw, status)), if (truncate) 1 else status and 3)
            when {
                rounded.isNaN() -> FloatingLane(0, INVALID_OPERATION)
                rounded < lowerBound -> FloatingLane(minimum, INVALID_OPERATION)
                rounded >= limit -> FloatingLane(maximum, INVALID_OPERATION)
                else -> {
                    val result = if (signed) rounded.toLong() else rounded.toULong().toLong()
                    FloatingLane(result, if (rounded != value) INEXACT else 0)
                }
            }
        }
    }
}

private fun fromIntegerInstruction(format: Int, suffix: Char, signed: Boolean): BasicInstruction {
    val precision = MsaPrecision(format)
    val field = (28 + (if (signed) 0 else 2) + format).toString(2)
    val mnemonic = if (signed) "ffint_s" else "ffint_u"
    return BasicInstruction(
        "$mnemonic.$suffix \$w1,\$w2", "Convert each integer element to floating point",
        R_FORMAT, "011110 11001 $field sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val input = context.coprocessor1.readVector(source)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val value = input.floatingLane(index, precision.bits, signed)
            val result = convertInteger(value, double = format != 0, status, unsigned = !signed)
            FloatingLane(result.bits, result.causes)
        }
    }
}

private fun fromFixedPointInstruction(format: Int, suffix: Char, left: Boolean): BasicInstruction {
    val precision = MsaPrecision(format)
    val mnemonic = if (left) "ffql" else "ffqr"
    val field = (20 + (if (left) 0 else 2) + format).toString(2)
    return BasicInstruction(
        "$mnemonic.$suffix \$w1,\$w2", "Convert a half-vector of signed fixed-point elements to floating point",
        R_FORMAT, "011110 11001 $field sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val input = context.coprocessor1.readVector(source)
        val selected = if (left) input.high else input.low
        val result = VectorRegister(
            fixedPointHalf(selected, 0, precision),
            fixedPointHalf(selected, 32, precision),
        )
        context.writeMsaStatus(statement, context.coprocessor1.msaStatus and CAUSE_MASK.inv())
        context.updateVectorRegister(destination, result)
    }
}


private fun fixedPointHalf(source: Long, offset: Int, precision: MsaPrecision): Long {
    val narrow = precision.bits / 2
    var result = 0L
    for (shift in 0 until Long.SIZE_BITS step precision.bits) {
        val value = element(source, offset + shift / 2, narrow, signed = true)
        result = result or (precision.raw(Math.scalb(value.toDouble(), 1 - narrow)) shl shift)
    }
    return result
}

private fun VectorRegister.floatingLane(index: Int, bits: Int, signed: Boolean = false): Long {
    val offset = index * bits
    return element(if (offset < 64) low else high, offset and 63, bits, signed)
}

private fun widenFloatingInstruction(format: Int, suffix: Char, left: Boolean): BasicInstruction {
    val precision = MsaPrecision(format)
    val mnemonic = if (left) "fexupl" else "fexupr"
    val field = (16 + (if (left) 0 else 2) + format).toString(2)
    val offset = if (left) 128 / precision.bits else 0
    return BasicInstruction(
        "$mnemonic.$suffix \$w1,\$w2", "Widen floating-point elements from one half of a vector",
        R_FORMAT, "011110 11001 $field sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val input = context.coprocessor1.readVector(source)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val raw = input.floatingLane(index + offset, precision.bits / 2).toInt()
            if (format != 0) {
                val result = widenSingle(raw, status)
                FloatingLane(result.bits, result.causes)
            } else if (raw and 0x7c00 == 0x7c00 && raw and 0x03ff != 0) {
                val sign = (raw and 0x8000).toLong() shl 16
                val payload = (raw and 0x03ff).toLong() shl 13
                val cause = if (raw and 0x0200 == 0) INVALID_OPERATION else 0
                FloatingLane(sign or 0x7fc00000L or payload, cause)
            } else {
                FloatingLane(float16ToFloat(raw.toShort()).toRawBits().toUInt().toLong())
            }
        }
    }
}

private fun narrowFloatingInstruction(format: Int, suffix: Char, fixedPoint: Boolean): BasicInstruction {
    val input = MsaPrecision(format)
    val bits = input.bits / 2
    val exponent = if (format == 0) 0x7c00L else 0x7f800000L
    val lanesPerSource = 128 / input.bits
    val name = if (fixedPoint) "ftq" else "fexdo"
    val field = (16 + (if (fixedPoint) 4 else 0) + format).toString(2)
    return BasicInstruction(
        "$name.$suffix \$w1,\$w2,\$w3", "Narrow and pack floating-point elements from two vectors",
        R_FORMAT, "011110 $field ttttt sssss fffff 011011",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val left = context.coprocessor1.readVector(source)
        val right = context.coprocessor1.readVector(target)
        context.writeFloatingVector(statement, destination, bits, exponent) { index, status ->
            val vector = if (index < lanesPerSource) right else left
            val raw = vector.floatingLane(index % lanesPerSource, input.bits)
            if (fixedPoint) {
                val value = input.number(raw)
                val scaled = Math.scalb(input.number(input.flush(raw, status)), bits - 1)
                val rounded = roundIntegral(scaled, status and 3)
                val limit = 1L shl (bits - 1)
                when {
                    rounded.isNaN() -> FloatingLane(0, INVALID_OPERATION)
                    rounded < -limit.toDouble() -> FloatingLane(-limit, OVERFLOW or INEXACT)
                    rounded >= limit.toDouble() -> FloatingLane(limit - 1, OVERFLOW or INEXACT)
                    else -> FloatingLane(rounded.toLong(), if (Math.scalb(value, bits - 1) != rounded) INEXACT else 0)
                }
            } else {
                val result = if (format == 0) narrowSingleToHalf(raw.toInt(), status) else narrowDouble(raw, status)
                FloatingLane(result.bits, result.causes)
            }
        }
    }
}

private enum class FloatingArithmetic(val mnemonic: String) {
    ADD("fadd"), SUBTRACT("fsub"), MULTIPLY("fmul"), DIVIDE("fdiv"),
    MULTIPLY_ADD("fmadd"), MULTIPLY_SUBTRACT("fmsub");

    val fused: Boolean get() = this == MULTIPLY_ADD || this == MULTIPLY_SUBTRACT

    fun nearest(a: Double, b: Double, accumulator: Double, single: Boolean): Double {
        if (single) {
            val x = a.toFloat()
            val y = b.toFloat()
            return when (this) {
                ADD -> x + y
                SUBTRACT -> x - y
                MULTIPLY -> x * y
                DIVIDE -> x / y
                MULTIPLY_ADD -> Math.fma(x, y, accumulator.toFloat())
                MULTIPLY_SUBTRACT -> Math.fma(-x, y, accumulator.toFloat())
            }.toDouble()
        }
        return when (this) {
            ADD -> a + b
            SUBTRACT -> a - b
            MULTIPLY -> a * b
            DIVIDE -> a / b
            MULTIPLY_ADD -> Math.fma(a, b, accumulator)
            MULTIPLY_SUBTRACT -> Math.fma(-a, b, accumulator)
        }
    }

    fun exact(a: BigDecimal, b: BigDecimal, accumulator: BigDecimal): BigDecimal = when (this) {
        ADD -> a + b
        SUBTRACT -> a - b
        MULTIPLY -> a * b
        DIVIDE -> a // The caller carries the denominator separately.
        MULTIPLY_ADD -> accumulator + a * b
        MULTIPLY_SUBTRACT -> accumulator - a * b
    }
}

private fun arithmeticInstruction(operation: FloatingArithmetic, format: Int, suffix: Char): BasicInstruction {
    val precision = MsaPrecision(format)
    val field = (operation.ordinal * 2 + format).toString(2).padStart(5, '0')
    return BasicInstruction(
        "${operation.mnemonic}.$suffix \$w1,\$w2,\$w3", "Apply floating-point arithmetic independently to each vector element",
        R_FORMAT, "011110 $field ttttt sssss fffff 011011",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val fpu = context.coprocessor1
        val left = fpu.readVector(source)
        val right = fpu.readVector(target)
        val previous = if (operation.fused) fpu.readVector(destination) else VectorRegister(0, 0)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val operands = if (operation.fused) listOf(previous, left, right) else listOf(left, right)
            val raw = operands.map { it.floatingLane(index, precision.bits) }
            val nan = raw.firstOrNull(precision::isSignaling) ?: raw.firstOrNull(precision::isNan)
            val a = precision.number(precision.flush(left.floatingLane(index, precision.bits), status))
            val b = precision.number(precision.flush(right.floatingLane(index, precision.bits), status))
            val accumulator = precision.number(precision.flush(previous.floatingLane(index, precision.bits), status))
            val flushed = if (raw.any { it != precision.flush(it, status) }) INEXACT else 0
            val invalidProduct = operation.fused && (a.isInfinite() && b == 0.0 || b.isInfinite() && a == 0.0)
            if (nan != null) {
                val invalid = if (precision.isSignaling(nan) || invalidProduct) INVALID_OPERATION else 0
                FloatingLane(nan or precision.quiet, invalid or flushed)
            } else {
                val result = evaluateArithmetic(operation, precision, a, b, accumulator, status)
                result.copy(causes = result.causes or flushed)
            }
        }
    }
}

private fun evaluateArithmetic(
    operation: FloatingArithmetic, precision: MsaPrecision, a: Double, b: Double, accumulator: Double, status: Int,
): FloatingLane {
    val single = precision.bits == 32
    val nearest = operation.nearest(a, b, accumulator, single)
    if (nearest.isNaN()) return FloatingLane(precision.exponent or precision.quiet, INVALID_OPERATION)
    if (!a.isFinite() || !b.isFinite() || !accumulator.isFinite()) return FloatingLane(precision.raw(nearest))
    if (operation == FloatingArithmetic.DIVIDE && b == 0.0) return FloatingLane(precision.raw(nearest), DIVIDE_BY_ZERO)
    var numerator = operation.exact(BigDecimal(a), BigDecimal(b), BigDecimal(accumulator))
    val denominator = if (operation == FloatingArithmetic.DIVIDE) BigDecimal(abs(b)) else BigDecimal.ONE
    if (operation == FloatingArithmetic.DIVIDE && b < 0.0) numerator = numerator.negate()
    val mode = status and 3
    if (numerator.signum() == 0) {
        val cancellation = when (operation) {
            FloatingArithmetic.ADD -> a.toRawBits() xor b.toRawBits() < 0
            FloatingArithmetic.SUBTRACT -> a.toRawBits() xor b.toRawBits() >= 0
            FloatingArithmetic.MULTIPLY_ADD, FloatingArithmetic.MULTIPLY_SUBTRACT -> {
                val productSign = (a.toRawBits() xor b.toRawBits() < 0) != (operation == FloatingArithmetic.MULTIPLY_SUBTRACT)
                productSign != (accumulator.toRawBits() < 0)
            }
            else -> false
        }
        return FloatingLane(precision.raw(if (mode == 3 && cancellation) -0.0 else nearest))
    }
    return roundFinite(precision, nearest, numerator, denominator, status)
}

private fun roundFinite(
    precision: MsaPrecision, nearest: Double, numerator: BigDecimal, denominator: BigDecimal, status: Int,
): FloatingLane {
    val single = precision.bits == 32
    val mode = status and 3
    val negative = numerator.signum() < 0
    val magnitude = numerator.abs()
    val normal = if (single) Math.scalb(1.0, -126) else Math.scalb(1.0, -1022)
    val minimumNormal = BigDecimal(normal).multiply(denominator)
    if (status and FLUSH_SUBNORMALS != 0 && magnitude < minimumNormal) {
        return FloatingLane(precision.raw(if (negative) -0.0 else 0.0), UNDERFLOW or INEXACT)
    }
    val relation = when {
        nearest == Double.POSITIVE_INFINITY -> 1
        nearest == Double.NEGATIVE_INFINITY -> -1
        else -> BigDecimal(nearest).multiply(denominator).compareTo(numerator)
    }
    val lower = relation > 0 && (mode == 3 || mode == 1 && !negative)
    val upper = relation < 0 && (mode == 2 || mode == 1 && negative)
    val rounded = when {
        lower -> if (single) Math.nextDown(nearest.toFloat()).toDouble() else Math.nextDown(nearest)
        upper -> if (single) Math.nextUp(nearest.toFloat()).toDouble() else Math.nextUp(nearest)
        else -> nearest
    }
    val outward = if (negative) mode == 3 else mode == 2
    val maximum = if (single) Float.MAX_VALUE.toDouble() else Double.MAX_VALUE
    val overflow = when {
        mode == 0 -> nearest.isInfinite()
        outward -> magnitude > BigDecimal(maximum).multiply(denominator)
        else -> magnitude >= BigDecimal(maximum).add(BigDecimal(Math.ulp(maximum).let {
            if (single) Math.ulp(maximum.toFloat()).toDouble() else it
        })).multiply(denominator)
    }
    val edge = BigDecimal(normal).divide(BigDecimal.valueOf(1L shl (if (single) 24 else 53))).multiply(denominator)
    val tiny = when {
        mode == 0 -> magnitude < minimumNormal.subtract(edge.divide(BigDecimal.TWO))
        outward -> magnitude <= minimumNormal.subtract(edge)
        else -> magnitude < minimumNormal
    }
    val causes = when {
        overflow -> OVERFLOW or if (status and (OVERFLOW shl 7) == 0) INEXACT else 0
        tiny && (relation != 0 || status and UNDERFLOW_ENABLE != 0) -> UNDERFLOW or if (relation != 0) INEXACT else 0
        relation != 0 -> INEXACT
        else -> 0
    }
    return FloatingLane(precision.raw(rounded), causes)
}

private enum class FloatingUnary(val mnemonic: String, val selector: Int) {
    SQUARE_ROOT("fsqrt", 6),
    RECIPROCAL_SQUARE_ROOT("frsqrt", 8),
    RECIPROCAL("frcp", 10),
    EXPONENT("flog2", 14),
}

private const val DIVIDE_BY_ZERO = 1 shl 3

private fun unaryInstruction(operation: FloatingUnary, format: Int, suffix: Char): BasicInstruction {
    val precision = MsaPrecision(format)
    val field = (operation.selector + format).toString(2).padStart(5, '0')
    return BasicInstruction(
        "${operation.mnemonic}.$suffix \$w1,\$w2", "Apply ${operation.mnemonic} to each floating-point element",
        R_FORMAT, "011110 11001 $field sssss fffff 011110",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val input = context.coprocessor1.readVector(source)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val raw = input.floatingLane(index, precision.bits)
            if (precision.isNan(raw)) {
                FloatingLane(raw or precision.quiet, if (precision.isSignaling(raw)) INVALID_OPERATION else 0)
            } else {
                val flushed = precision.flush(raw, status)
                val value = precision.number(flushed)
                val result = when (operation) {
                    FloatingUnary.SQUARE_ROOT -> squareRoot(precision, value, status)
                    FloatingUnary.RECIPROCAL -> evaluateArithmetic(FloatingArithmetic.DIVIDE, precision, 1.0, value, 0.0, status)
                    FloatingUnary.RECIPROCAL_SQUARE_ROOT -> {
                        val root = squareRoot(precision, value, status)
                        if (root.causes and INVALID_OPERATION != 0) root
                        else {
                            val reciprocal = evaluateArithmetic(
                                FloatingArithmetic.DIVIDE, precision, 1.0, precision.number(root.bits), 0.0, status,
                            )
                            reciprocal.copy(causes = reciprocal.causes or root.causes)
                        }
                    }
                    FloatingUnary.EXPONENT -> when {
                        value == 0.0 -> FloatingLane(precision.raw(Double.NEGATIVE_INFINITY), DIVIDE_BY_ZERO)
                        value.isInfinite() -> FloatingLane(precision.raw(Double.POSITIVE_INFINITY))
                        else -> {
                            val exponent = if (abs(value) < DOUBLE_MIN_NORMAL) {
                                Math.getExponent(Math.scalb(value, 54)) - 54
                            } else Math.getExponent(value)
                            FloatingLane(precision.raw(exponent.toDouble()))
                        }
                    }
                }
                result.copy(causes = result.causes or if (flushed != raw) INEXACT else 0)
            }
        }
    }
}

private fun squareRoot(precision: MsaPrecision, value: Double, status: Int): FloatingLane {
    if (value < 0.0) return FloatingLane(precision.exponent or precision.quiet, INVALID_OPERATION)
    if (value == 0.0 || value.isInfinite()) return FloatingLane(precision.raw(value))
    val single = precision.bits == 32
    val nearest = Math.sqrt(value).let { if (single) it.toFloat().toDouble() else it }
    val exact = BigDecimal(value)
    val candidate = BigDecimal(nearest)
    val relation = (candidate * candidate).compareTo(exact)
    val rounded = when {
        relation > 0 && (status and 3 == 1 || status and 3 == 3) ->
            if (single) Math.nextDown(nearest.toFloat()).toDouble() else Math.nextDown(nearest)
        relation < 0 && status and 3 == 2 ->
            if (single) Math.nextUp(nearest.toFloat()).toDouble() else Math.nextUp(nearest)
        else -> nearest
    }
    return FloatingLane(precision.raw(rounded), if (relation != 0) INEXACT else 0)
}

private fun scaleInstruction(format: Int, suffix: Char): BasicInstruction {
    val precision = MsaPrecision(format)
    return BasicInstruction(
        "fexp2.$suffix \$w1,\$w2,\$w3", "Scale each floating-point element by a signed power of two",
        R_FORMAT, "011110 0111$format ttttt sssss fffff 011011",
    ) { statement, context ->
        val (destination, source, target) = statement.operands
        val input = context.coprocessor1.readVector(source)
        val powers = context.coprocessor1.readVector(target)
        context.writeFloatingVector(statement, destination, precision) { index, status ->
            val raw = input.floatingLane(index, precision.bits)
            val flushed = precision.flush(raw, status)
            val value = precision.number(flushed)
            when {
                precision.isNan(raw) -> FloatingLane(raw or precision.quiet, if (precision.isSignaling(raw)) INVALID_OPERATION else 0)
                value == 0.0 || value.isInfinite() -> FloatingLane(flushed, if (flushed != raw) INEXACT else 0)
                else -> {
                    // Beyond this magnitude every nonzero finite input overflows or underflows.
                    val power = powers.floatingLane(index, precision.bits, signed = true).coerceIn(-4096, 4096).toInt()
                    val single = precision.bits == 32
                    val nearest = if (single) Math.scalb(value.toFloat(), power).toDouble() else Math.scalb(value, power)
                    val factor = BigDecimal.TWO.pow(abs(power))
                    val numerator = if (power >= 0) BigDecimal(value) * factor else BigDecimal(value)
                    val denominator = if (power >= 0) BigDecimal.ONE else factor
                    roundFinite(precision, nearest, numerator, denominator, status)
                }
            }
        }
    }
}
