package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCEqS2 : BasicInstruction(
    "c.eq.s 1,\$f0,\$f1",
    "Compare equal single precision : If \$f0 is equal to \$f1, set Coprocessor 1 condition flag"
        + " specied by immediate to true else set it to false",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 ttttt sssss fff 00 11 0010",
    { statement, context ->
        val operands = statement.operands
        val left = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        val right = Float.fromBits(context.coprocessor1.getValue(operands[2]))
        if (left == right) {
            context.setConditionFlag(operands[0])
        } else {
            context.clearConditionFlag(operands[0])
        }
    },
)
