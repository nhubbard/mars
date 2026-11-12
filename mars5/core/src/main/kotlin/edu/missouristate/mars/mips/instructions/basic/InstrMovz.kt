package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovz : BasicInstruction(
    "movz \$t1,\$t2,\$t3",
    "Move conditional zero : Set \$t1 to \$t2 if \$t3 is zero",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 001010",
    { statement, context ->
        val operands = statement.operands
        if (context.registerFile.getValue(operands[2]) == 0) {
            context.updateRegister(operands[0], context.registerFile.getValue(operands[1]))
        }
    },
)
