package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.FloatingArithmetic
import edu.missouristate.mars.mips.instructions.FloatingLane
import edu.missouristate.mars.mips.instructions.FloatingPrecision
import edu.missouristate.mars.mips.instructions.evaluateArithmetic
import edu.missouristate.mars.mips.instructions.squareRoot
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INEXACT
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.INVALID_OPERATION

internal fun release6ScalarArithmetic(): List<BasicInstruction> = buildList {
    val operations = listOf(
        Triple("add", "000000", FloatingArithmetic.ADD),
        Triple("sub", "000001", FloatingArithmetic.SUBTRACT),
        Triple("mul", "000010", FloatingArithmetic.MULTIPLY),
        Triple("div", "000011", FloatingArithmetic.DIVIDE),
        Triple("maddf", "011000", FloatingArithmetic.MULTIPLY_ADD),
        Triple("msubf", "011001", FloatingArithmetic.MULTIPLY_SUBTRACT),
    )
    for ((mnemonic, function, operation) in operations) {
        for ((format, suffix) in "sd".withIndex()) {
            val precision = FloatingPrecision(32 shl format)
            add(BasicInstruction(
                $$"$$mnemonic.$$suffix $f2,$f4,$f6", "Perform floating-point $mnemonic using FCSR rounding and exceptions",
                R_FORMAT, "010001 1000$format ttttt sssss fffff $function",
            ) { statement, context ->
                val (destination, left, right) = statement.operands
                val fpu = context.coprocessor1
                fun read(register: Int): Long = if (format == 0) fpu.getValue(register).toUInt().toLong()
                else fpu.getLongFromRegisterPair(register)

                val previous = if (operation.fused) read(destination) else 0L
                val result = operation.evaluate(precision, read(left), read(right), previous, fpu.floatingStatus)
                context.recordFloatingExceptions(statement, result.causes)
                if (format == 0) context.updateCoprocessor1(destination, result.bits.toInt())
                else context.writeDoubleBits(destination, result.bits)
            })
        }
    }
}

internal fun release6ScalarRoots(): List<BasicInstruction> = buildList {
    for ((format, suffix) in "sd".withIndex()) {
        val precision = FloatingPrecision(32 shl format)
        for (operation in ScalarRoot.entries) {
            add(BasicInstruction(
                $$"$${operation.mnemonic}.$$suffix $f2,$f4", operation.description,
                R_FORMAT, "010001 1000$format 00000 sssss fffff ${operation.function}",
            ) { statement, context ->
                val (destination, source) = statement.operands
                val fpu = context.coprocessor1
                val raw = if (format == 0) fpu.getValue(source).toUInt().toLong() else fpu.getLongFromRegisterPair(source)
                val status = fpu.floatingStatus
                val result = if (precision.isNan(raw)) {
                    FloatingLane(raw or precision.quiet, if (precision.isSignaling(raw)) INVALID_OPERATION else 0)
                } else {
                    val input = precision.flush(raw, status)
                    val value = precision.number(input)
                    val evaluated = operation.evaluate(precision, value, status)
                    evaluated.copy(causes = evaluated.causes or if (raw != input) INEXACT else 0)
                }
                context.recordFloatingExceptions(statement, result.causes)
                if (format == 0) context.updateCoprocessor1(destination, result.bits.toInt())
                else context.writeDoubleBits(destination, result.bits)
            })
        }
    }
}

private enum class ScalarRoot(val mnemonic: String, val function: String, val description: String) {
    ROOT("sqrt", "000100", "Compute a correctly rounded square root"),
    RECIPROCAL("recip", "010101", "Compute a reciprocal"),
    RECIPROCAL_ROOT("rsqrt", "010110", "Approximate the reciprocal square root");

    fun evaluate(precision: FloatingPrecision, value: Double, status: Int): FloatingLane = when (this) {
        ROOT -> squareRoot(precision, value, status)
        RECIPROCAL -> evaluateArithmetic(FloatingArithmetic.DIVIDE, precision, 1.0, value, 0.0, status)
        RECIPROCAL_ROOT -> {
            // Release 6 allows a rounding-mode independent approximation within two ULPs.
            val nearestStatus = status and 3.inv()
            val root = squareRoot(precision, value, nearestStatus)
            if (root.causes and INVALID_OPERATION != 0) root
            else {
                val result = evaluateArithmetic(
                    FloatingArithmetic.DIVIDE, precision, 1.0, precision.number(root.bits), 0.0, nearestStatus,
                )
                result.copy(causes = result.causes or root.causes)
            }
        }
    }
}
