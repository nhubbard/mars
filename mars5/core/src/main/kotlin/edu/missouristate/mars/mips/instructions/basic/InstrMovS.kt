package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovS : BasicInstruction(
    "mov.s \$f0,\$f1",
    "Move floating point single precision : Set single precision \$f0 to single precision value"
        + " in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 000110",
    { statement, context ->
        val operands = statement.operands
        context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]))
    },
)
