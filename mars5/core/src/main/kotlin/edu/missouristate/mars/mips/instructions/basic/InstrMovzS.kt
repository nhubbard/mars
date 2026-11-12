package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovzS : BasicInstruction(
    "movz.s \$f0,\$f1,\$t3",
    "Move floating point single precision : If \$t3 is zero, set single precision \$f0 to single"
        + " precision value in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 ttttt sssss fffff 010010",
    { statement, context ->
        val operands = statement.operands
        if (context.registerFile.getValue(operands[2]) == 0) {
            context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]))
        }
    },
)
