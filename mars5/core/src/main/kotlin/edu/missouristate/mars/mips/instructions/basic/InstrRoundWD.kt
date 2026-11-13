package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import kotlin.math.roundToInt

open class InstrRoundWD : BasicInstruction(
    "round.w.d \$f1,\$f2",
    "Round double precision to word : Set \$f1 to 32-bit integer round of double-precision float"
        + " in \$f2",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 00000 sssss fffff 001100",
    { statement, context ->
        val operands = statement.operands
        if (operands[1] % 2 == 1) {
            throw ProcessingException(context, statement, "second register must be even-numbered")
        }
        val value = context.readDoubleOperand(operands[1])
        val result = if (!value.isFinite() || value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) {
            Int.MAX_VALUE
        } else {
            val whole = value.toInt()
            val below = if (value < 0.0) whole - 1 else whole
            val above = if (value < 0.0) whole else whole + 1
            if (value - below == above - value) {
                if (above % 2 == 0) above else below
            } else {
                value.roundToInt()
            }
        }
        context.updateCoprocessor1(operands[0], result)
    },
)
