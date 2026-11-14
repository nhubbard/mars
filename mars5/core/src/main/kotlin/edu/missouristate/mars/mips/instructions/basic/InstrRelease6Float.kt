package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import java.lang.Math.fma
import kotlin.math.abs

internal val release6FloatFactories: List<() -> BasicInstruction> =
    Release6FloatOperation.entries.flatMap { operation ->
        listOf({ InstrRelease6Float(operation, false) }, { InstrRelease6Float(operation, true) })
    }

internal class InstrRelease6Float(operation: Release6FloatOperation, double: Boolean) : BasicInstruction(
    "${operation.mnemonic}.${if (double) "d" else "s"} \$f2,\$f4,\$f6",
    operation.description,
    R_FORMAT,
    "010001 ${if (double) "10001" else "10000"} ttttt sssss fffff ${operation.encoding}",
    { statement, context ->
        val (destination, left, right) = statement.operands
        val fpu = context.coprocessor1
        if (double) {
            if (!context.coprocessor1.hasWideRegisters && ((destination or left or right) and 1 != 0)) {
                throw ProcessingException(context, statement, "all registers must be even-numbered")
            }
            val bits = operation.apply(
                fpu.getLongFromRegisterPair(left), fpu.getLongFromRegisterPair(right),
                if (operation.usesDestination) fpu.getLongFromRegisterPair(destination) else 0L, double = true,
            )
            context.writeDoubleBits(destination, bits)
        } else {
            val bits = operation.apply(
                fpu.getValue(left).toLong(), fpu.getValue(right).toLong(),
                if (operation.usesDestination) fpu.getValue(destination).toLong() else 0L,
                double = false,
            )
            context.updateCoprocessor1(destination, bits.toInt())
        }
    },
)

internal enum class Release6FloatOperation(val mnemonic: String, val encoding: String, val description: String) {
    SELECT("sel", "010000", "Select a floating-point value using bit 0 of the destination"),
    SELECT_ZERO("seleqz", "010100", "Copy a floating-point value when bit 0 of the condition is clear, else set zero"),
    SELECT_NONZERO("selnez", "010111", "Copy a floating-point value when bit 0 of the condition is set, else set zero"),
    MULTIPLY_ADD("maddf", "011000", "Fused multiply-add into the destination"),
    MULTIPLY_SUBTRACT("msubf", "011001", "Subtract a product from the destination with one rounding"),
    MINIMUM("min", "011100", "Select the smaller floating-point value"),
    MINIMUM_MAGNITUDE("mina", "011101", "Select the floating-point value with smaller magnitude"),
    MAXIMUM("max", "011110", "Select the larger floating-point value"),
    MAXIMUM_MAGNITUDE("maxa", "011111", "Select the floating-point value with larger magnitude");

    val usesDestination: Boolean get() = this == SELECT || this == MULTIPLY_ADD || this == MULTIPLY_SUBTRACT

    fun apply(left: Long, right: Long, destination: Long, double: Boolean): Long = when (this) {
        SELECT -> if (destination and 1L == 0L) left else right
        SELECT_ZERO -> if (right and 1L == 0L) left else 0L
        SELECT_NONZERO -> if (right and 1L != 0L) left else 0L
        MULTIPLY_ADD, MULTIPLY_SUBTRACT -> if (double) {
            val value = Double.fromBits(left)
            fma(if (this == MULTIPLY_SUBTRACT) -value else value, Double.fromBits(right), Double.fromBits(destination)).toBits()
        } else {
            val value = Float.fromBits(left.toInt())
            fma(if (this == MULTIPLY_SUBTRACT) -value else value, Float.fromBits(right.toInt()), Float.fromBits(destination.toInt()))
                .toBits().toLong()
        }
        else -> {
            val a = if (double) Double.fromBits(left) else Float.fromBits(left.toInt()).toDouble()
            val b = if (double) Double.fromBits(right) else Float.fromBits(right.toInt()).toDouble()
            when {
                b.isNaN() -> left
                a.isNaN() -> right
                else -> {
                    val order = if (this == MINIMUM_MAGNITUDE || this == MAXIMUM_MAGNITUDE) {
                        abs(a).compareTo(abs(b)).takeIf { it != 0 } ?: a.compareTo(b)
                    } else a.compareTo(b)
                    val minimum = this == MINIMUM || this == MINIMUM_MAGNITUDE
                    if (if (minimum) order <= 0 else order >= 0) left else right
                }
            }
        }
    }
}
