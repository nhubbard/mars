package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCLtS2 : BasicInstruction(
    "c.lt.s 1,\$f0,\$f1",
    "Compare less than single precision : If \$f0 is less than \$f1, set Coprocessor 1 condition"
        + " flag specified by immediate to true else set it to false",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 ttttt sssss fff 00 111100",
    { statement, context ->
        val operands = statement.operands
        val left = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        val right = Float.fromBits(context.coprocessor1.getValue(operands[2]))
        if (left < right) {
            context.setConditionFlag(operands[0])
        } else {
            context.clearConditionFlag(operands[0])
        }
    },
)
