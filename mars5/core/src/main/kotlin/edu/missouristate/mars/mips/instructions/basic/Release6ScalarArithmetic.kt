package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.FloatingArithmetic
import edu.missouristate.mars.mips.instructions.FloatingPrecision

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
                "$mnemonic.$suffix \$f2,\$f4,\$f6", "Perform floating-point $mnemonic using FCSR rounding and exceptions",
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
