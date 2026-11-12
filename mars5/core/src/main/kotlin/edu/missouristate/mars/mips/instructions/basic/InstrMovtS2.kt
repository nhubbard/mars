package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovtS2 : BasicInstruction(
    "movt.s \$f0,\$f1,1",
    "Move floating point single precision : If condition flag specified by immediate is true,"
        + " set single precision \$f0 to single precision value in \$f1e",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 ttt 01 sssss fffff 010001",
    { statement, context ->
        val operands = statement.operands
        if (context.coprocessor1.getConditionFlag(operands[2]) == 1) {
            context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]))
        }
    },
)
