package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCLeS1 : BasicInstruction(
    "c.le.s \$f0,\$f1",
    "Compare less or equal single precision : If \$f0 is less than or equal to \$f1, set"
        + " Coprocessor 1 condition flag 0 true else set it false",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 sssss fffff 00000 111110",
    { statement, context ->
        val operands = statement.operands
        val left = Float.fromBits(context.coprocessor1.getValue(operands[0]))
        val right = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        if (left <= right) {
            context.setConditionFlag(0)
        } else {
            context.clearConditionFlag(0)
        }
    },
)
