package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.FLUSH_SUBNORMALS
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INEXACT
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import java.lang.Double.MIN_NORMAL as DOUBLE_MIN_NORMAL
import java.lang.Float.MIN_NORMAL as FLOAT_MIN_NORMAL
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.truncate

internal fun release6FixedPointConversions(): List<BasicInstruction> = buildList {
    for ((name, operation) in listOf("round" to 8, "trunc" to 9, "ceil" to 10, "floor" to 11, "cvt" to 37)) {
        for (double in listOf(false, true)) {
            add(fixedPointConversion(name, operation, double, longword = false))
            add(fixedPointConversion(name, operation, double, longword = true))
        }
    }
}

private fun fixedPointConversion(name: String, operation: Int, double: Boolean, longword: Boolean): BasicInstruction {
    val function = (if (longword) operation else if (name == "cvt") 36 else operation + 4).toString(2).padStart(6, '0')
    val destinationFormat = if (longword) "l" else "w"
    val sourceFormat = if (double) "d" else "s"
    val limit = if (longword) 9_223_372_036_854_775_808.0 else 2_147_483_648.0
    val maximum = if (longword) Long.MAX_VALUE else Int.MAX_VALUE.toLong()
    val minimum = if (longword) Long.MIN_VALUE else Int.MIN_VALUE.toLong()
    return BasicInstruction(
        "$name.$destinationFormat.$sourceFormat \$f2,\$f4",
        "Convert a floating-point value to a signed ${if (longword) "64-bit" else "32-bit"} integer",
        R_FORMAT, "010001 ${if (double) "10001" else "10000"} 00000 sssss fffff $function",
    ) { statement, context ->
        val (destination, source) = statement.operands
        val status = context.coprocessor1.floatingStatus
        val input = if (double) context.readDoubleOperand(source) else context.coprocessor1.getFloatFromRegister(source).toDouble()
        val minNormal = if (double) DOUBLE_MIN_NORMAL else FLOAT_MIN_NORMAL.toDouble()
        val value = if (status and FLUSH_SUBNORMALS != 0 && input > -minNormal && input < minNormal) 0.0 else input
        val mode = if (name == "cvt") status and 3 else operation - 8
        val rounded = when (mode) {
            0 -> Math.rint(value)
            1 -> truncate(value)
            2 -> ceil(value)
            else -> floor(value)
        }
        val invalid = rounded.isNaN() || rounded < -limit || rounded >= limit
        val causes = when {
            invalid -> INVALID_OPERATION
            rounded != value -> INEXACT
            else -> 0
        }
        val result = when {
            rounded.isNaN() -> 0L
            rounded < -limit -> minimum
            rounded >= limit -> maximum
            else -> rounded.toLong()
        }
        context.recordFloatingExceptions(statement, causes)
        if (longword) context.updateCoprocessor1Doubleword(destination, result)
        else context.updateCoprocessor1(destination, result.toInt())
    }
}
