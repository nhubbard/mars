package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrFloorWS : BasicInstruction(
    "floor.w.s \$f0,\$f1",
    "Floor single precision to word : Set \$f0 to 32-bit integer floor of single-precision float"
        + " in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 001111",
    { statement, context ->
        val operands = statement.operands
        val value = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        val result = if (!value.isFinite() || value < Int.MIN_VALUE.toFloat() || value > Int.MAX_VALUE.toFloat()) {
            Int.MAX_VALUE
        } else {
            Math.floor(value.toDouble()).toInt()
        }
        context.updateCoprocessor1(operands[0], result)
    },
)
