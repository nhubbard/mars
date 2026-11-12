package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovfS1 : BasicInstruction(
    "movf.s \$f0,\$f1",
    "Move floating point single precision : If condition flag 0 is false, set single precision"
        + " \$f0 to single precision value in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 000 00 sssss fffff 010001",
    { statement, context ->
        val operands = statement.operands
        if (context.coprocessor1.getConditionFlag(0) == 0) {
            context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]))
        }
    },
)
