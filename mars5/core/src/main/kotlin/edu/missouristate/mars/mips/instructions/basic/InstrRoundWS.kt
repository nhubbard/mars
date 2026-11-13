package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import kotlin.math.roundToInt

open class InstrRoundWS : BasicInstruction(
    "round.w.s \$f0,\$f1",
    "Round single precision to word : Set \$f0 to 32-bit integer round of single-precision float"
        + " in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 001100",
    { statement, context ->
        val operands = statement.operands
        val value = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        val result = if (!value.isFinite() || value < Int.MIN_VALUE.toFloat() || value > Int.MAX_VALUE.toFloat()) {
            Int.MAX_VALUE
        } else {
            val whole = value.toInt()
            val below = if (value < 0.0f) whole - 1 else whole
            val above = if (value < 0.0f) whole else whole + 1
            if (value - below == above - value) {
                if (above % 2 == 0) above else below
            } else {
                value.roundToInt()
            }
        }
        context.updateCoprocessor1(operands[0], result)
    },
)
