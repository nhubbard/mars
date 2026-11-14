package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.DIVIDE_BY_ZERO
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.FLUSH_SUBNORMALS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INEXACT
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.OVERFLOW
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.UNDERFLOW
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.UNDERFLOW_ENABLE
import java.math.BigDecimal
import kotlin.math.abs

internal class FloatingPrecision(val bits: Int) {
    val exponent = if (bits == 32) 0x7f800000L else 0x7ff0000000000000L
    val quiet = if (bits == 32) 1L shl 22 else 1L shl 51
    private val fraction = (quiet shl 1) - 1
    private val sign = 1L shl (bits - 1)

    fun isNan(raw: Long) = raw and exponent == exponent && raw and fraction != 0L
    fun isSignaling(raw: Long) = isNan(raw) && raw and quiet == 0L
    fun number(raw: Long) = if (bits == 32) Float.fromBits(raw.toInt()).toDouble() else Double.fromBits(raw)
    fun raw(value: Double) = if (bits == 32) value.toFloat().toRawBits().toUInt().toLong() else value.toRawBits()

    fun flush(raw: Long, status: Int): Long =
        if (status and FLUSH_SUBNORMALS != 0 && raw and exponent == 0L) raw and sign else raw
}

internal data class FloatingLane(val bits: Long, val causes: Int = 0)

internal enum class FloatingArithmetic {
    ADD, SUBTRACT, MULTIPLY, DIVIDE,
    MULTIPLY_ADD, MULTIPLY_SUBTRACT;

    val fused: Boolean get() = this == MULTIPLY_ADD || this == MULTIPLY_SUBTRACT

    fun evaluate(precision: FloatingPrecision, left: Long, right: Long, previous: Long, status: Int): FloatingLane {
        val raw = if (fused) listOf(previous, left, right) else listOf(left, right)
        val nan = raw.firstOrNull(precision::isSignaling) ?: raw.firstOrNull(precision::isNan)
        val a = precision.number(precision.flush(left, status))
        val b = precision.number(precision.flush(right, status))
        val accumulator = precision.number(precision.flush(previous, status))
        val flushed = if (raw.any { it != precision.flush(it, status) }) INEXACT else 0
        if (nan != null) {
            val invalidProduct = fused && (a.isInfinite() && b == 0.0 || b.isInfinite() && a == 0.0)
            val invalid = if (precision.isSignaling(nan) || invalidProduct) INVALID_OPERATION else 0
            return FloatingLane(nan or precision.quiet, invalid or flushed)
        }
        val result = evaluateArithmetic(this, precision, a, b, accumulator, status)
        return result.copy(causes = result.causes or flushed)
    }

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

internal fun evaluateArithmetic(
    operation: FloatingArithmetic, precision: FloatingPrecision, a: Double, b: Double, accumulator: Double, status: Int,
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

internal fun roundFinite(
    precision: FloatingPrecision, nearest: Double, numerator: BigDecimal, denominator: BigDecimal, status: Int,
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

internal fun squareRoot(precision: FloatingPrecision, value: Double, status: Int): FloatingLane {
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
