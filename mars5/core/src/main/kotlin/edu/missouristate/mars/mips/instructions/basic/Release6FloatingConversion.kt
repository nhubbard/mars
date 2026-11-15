package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.FLUSH_SUBNORMALS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INEXACT
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.OVERFLOW
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.UNDERFLOW
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.UNDERFLOW_ENABLE
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import java.lang.Double.MIN_NORMAL as DOUBLE_MIN_NORMAL
import java.lang.Float.MIN_NORMAL as FLOAT_MIN_NORMAL
import java.lang.Float.float16ToFloat
import java.lang.Float.floatToFloat16
import java.math.BigDecimal
import kotlin.math.abs

internal fun release6FloatingConversions(): List<BasicInstruction> =
    listOf(
        floatingConversion("s", "d", "10001"),
        floatingConversion("s", "w", "10100"),
        floatingConversion("s", "l", "10101"),
        floatingConversion("d", "s", "10000"),
        floatingConversion("d", "w", "10100"),
        floatingConversion("d", "l", "10101"),
    )

private fun floatingConversion(destinationFormat: String, sourceFormat: String, formatField: String): BasicInstruction {
    val double = destinationFormat == "d"
    return BasicInstruction(
        $$"cvt.$$destinationFormat.$$sourceFormat $f2,$f4",
        "Convert to ${if (double) "double" else "single"} precision floating point",
        R_FORMAT, "010001 $formatField 00000 sssss fffff ${if (double) "100001" else "100000"}",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val fpu = context.coprocessor1
        val status = fpu.floatingStatus
        val result = when (sourceFormat) {
            "s" -> widenSingle(fpu.getValue(source), status)
            "d" -> narrowDouble(fpu.getLongFromRegisterPair(source), status)
            "w" -> convertInteger(fpu.getValue(source).toLong(), double, status)
            else -> convertInteger(fpu.getLongFromRegisterPair(source), double, status)
        }
        context.recordFloatingExceptions(statement, result.causes)
        if (double) context.updateCoprocessor1Doubleword(destination, result.bits)
        else context.updateCoprocessor1(destination, result.bits.toInt())
    }
}

internal data class FloatingConversionResult(val bits: Long, val causes: Int = 0)

internal fun widenSingle(bits: Int, status: Int): FloatingConversionResult {
    val value = Float.fromBits(bits)
    if (value.isNaN()) {
        val payload = (bits.toLong() and 0x007f_ffff) shl 29
        val result = ((bits.toLong() and Int.MIN_VALUE.toUInt().toLong()) shl 32) or 0x7ff8_0000_0000_0000L or payload
        return FloatingConversionResult(result, if (bits and (1 shl 22) == 0) INVALID_OPERATION else 0)
    }
    if (status and FLUSH_SUBNORMALS != 0 && value != 0f && abs(value) < FLOAT_MIN_NORMAL) {
        return FloatingConversionResult(Math.copySign(0.0, value.toDouble()).toRawBits(), INEXACT)
    }
    return FloatingConversionResult(value.toDouble().toRawBits())
}

internal fun convertInteger(value: Long, double: Boolean, status: Int, unsigned: Boolean = false): FloatingConversionResult {
    // An exact comparison matters for longword inputs above the consecutive-integer range of Double.
    val exact = if (unsigned && value < 0) BigDecimal(value.toULong().toString()) else BigDecimal.valueOf(value)
    val nearest = if (double) exact.toDouble() else exact.toFloat().toDouble()
    val relation = BigDecimal(nearest).compareTo(exact)
    val rounded = directedRound(nearest, relation, exact.signum() < 0, double, status and 3)
    return FloatingConversionResult(floatingBits(rounded, double), if (relation == 0) 0 else INEXACT)
}

internal fun narrowSingleToHalf(bits: Int, status: Int): FloatingConversionResult {
    val value = Float.fromBits(bits)
    val sign = (bits ushr 16) and 0x8000
    if (value.isNaN()) {
        val result = sign or 0x7e00 or ((bits ushr 13) and 0x03ff)
        val cause = if (bits and 0x0040_0000 == 0) INVALID_OPERATION else 0
        return FloatingConversionResult(result.toLong(), cause)
    }
    val magnitude = abs(value).toDouble()
    if (magnitude == 0.0 || value.isInfinite()) {
        return FloatingConversionResult((sign or if (value.isInfinite()) 0x7c00 else 0).toLong())
    }
    if (status and FLUSH_SUBNORMALS != 0 && magnitude < FLOAT_MIN_NORMAL) {
        return FloatingConversionResult(sign.toLong(), INEXACT)
    }

    val mode = status and 3
    val outward = if (sign != 0) mode == 3 else mode == 2
    val nearestBits = floatToFloat16(abs(value)).toInt()
    val nearest = float16ToFloat(nearestBits.toShort()).toDouble()
    val relation = nearest.compareTo(magnitude)
    val rounded = when {
        mode == 0 -> nearestBits
        outward && relation < 0 -> nearestBits + 1
        !outward && relation > 0 -> nearestBits - 1
        else -> nearestBits
    }
    val overflow = when {
        mode == 0 -> nearest.isInfinite()
        outward -> magnitude > 65504.0
        else -> magnitude >= 65536.0
    }
    val minimumNormal = Math.scalb(1.0, -14)
    val edgeGap = Math.scalb(1.0, -25)
    val tiny = when {
        mode == 0 -> magnitude < minimumNormal - edgeGap / 2
        outward -> magnitude <= minimumNormal - edgeGap
        else -> magnitude < minimumNormal
    }
    return FloatingConversionResult((sign or rounded).toLong(), narrowingCauses(overflow, tiny, relation != 0, status))
}

internal fun narrowDouble(bits: Long, status: Int): FloatingConversionResult {
    val value = Double.fromBits(bits)
    if (value.isNaN()) {
        val payload = ((bits ushr 29) and 0x007f_ffff).toInt()
        val result = ((bits ushr 32).toInt() and Int.MIN_VALUE) or 0x7fc0_0000 or payload
        return FloatingConversionResult(result.toLong(), if (bits and (1L shl 51) == 0L) INVALID_OPERATION else 0)
    }
    if (!value.isFinite() || value == 0.0) return FloatingConversionResult(value.toFloat().toRawBits().toLong())

    val magnitude = abs(value)
    val minimumNormal = FLOAT_MIN_NORMAL.toDouble()
    if (status and FLUSH_SUBNORMALS != 0 && magnitude < minimumNormal) {
        val causes = if (magnitude < DOUBLE_MIN_NORMAL) INEXACT else UNDERFLOW or INEXACT
        return FloatingConversionResult(Math.copySign(0f, value.toFloat()).toRawBits().toLong(), causes)
    }

    val mode = status and 3
    val nearest = value.toFloat().toDouble()
    val relation = nearest.compareTo(value)
    val rounded = directedRound(nearest, relation, value < 0, double = false, mode)
    val outward = if (value < 0) mode == 3 else mode == 2
    val maximum = Float.MAX_VALUE.toDouble()
    val overflow = when {
        mode == 0 -> nearest.isInfinite()
        outward -> magnitude > maximum
        else -> magnitude >= Math.scalb(1.0, 128)
    }

    // Tininess is measured after rounding with an unbounded exponent.
    val edgeGap = Math.scalb(1.0, -150)
    val tiny = when {
        mode == 0 -> magnitude < minimumNormal - edgeGap / 2
        outward -> magnitude <= minimumNormal - edgeGap
        else -> magnitude < minimumNormal
    }
    return FloatingConversionResult(rounded.toFloat().toRawBits().toLong(), narrowingCauses(overflow, tiny, relation != 0, status))
}

private fun narrowingCauses(overflow: Boolean, tiny: Boolean, inexact: Boolean, status: Int): Int = when {
    overflow -> OVERFLOW or if (status and (OVERFLOW shl 7) == 0) INEXACT else 0
    tiny && (inexact || status and UNDERFLOW_ENABLE != 0) -> UNDERFLOW or if (inexact) INEXACT else 0
    inexact -> INEXACT
    else -> 0
}

private fun floatingBits(value: Double, double: Boolean): Long =
    if (double) value.toRawBits() else value.toFloat().toRawBits().toLong()

private fun directedRound(nearest: Double, relation: Int, negative: Boolean, double: Boolean, mode: Int): Double {
    val lower = relation > 0 && (mode == 3 || mode == 1 && !negative)
    val upper = relation < 0 && (mode == 2 || mode == 1 && negative)
    return when {
        lower -> if (double) Math.nextDown(nearest) else Math.nextDown(nearest.toFloat()).toDouble()
        upper -> if (double) Math.nextUp(nearest) else Math.nextUp(nearest.toFloat()).toDouble()
        else -> nearest
    }
}
