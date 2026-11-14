package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.CAUSE_MASK
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.FLUSH_SUBNORMALS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INEXACT
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.MSA_NON_TRAPPING
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.OVERFLOW
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.basic.convertInteger
import edu.missouristate.mars.mips.instructions.basic.floatingClass
import edu.missouristate.mars.mips.instructions.basic.narrowDouble
import edu.missouristate.mars.mips.instructions.basic.narrowSingleToHalf
import edu.missouristate.mars.mips.instructions.basic.roundIntegral
import edu.missouristate.mars.mips.instructions.basic.widenSingle
import java.lang.Float.float16ToFloat
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
