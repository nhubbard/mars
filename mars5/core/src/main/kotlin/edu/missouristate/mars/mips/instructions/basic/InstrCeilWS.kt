package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCeilWS : BasicInstruction(
    "ceil.w.s \$f0,\$f1",
    "Ceiling single precision to word : Set \$f0 to 32-bit integer ceiling of single-precision"
        + " float in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 001110",
    { statement, context ->
        val operands = statement.operands
        val value = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        val result = if (!value.isFinite() || value < Int.MIN_VALUE.toFloat() || value > Int.MAX_VALUE.toFloat()) {
            Int.MAX_VALUE
        } else {
            Math.ceil(value.toDouble()).toInt()
        }
        context.updateCoprocessor1(operands[0], result)
    },
)
