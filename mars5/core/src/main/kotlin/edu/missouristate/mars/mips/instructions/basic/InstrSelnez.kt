package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

class InstrSelnez : BasicInstruction(
    "selnez \$t1,\$t2,\$t3",
    "Select a register value when the condition register is nonzero",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 110111",
    { statement, context ->
        val operands = statement.operands
        val value = context.registerFile.getValue(operands[1])
        val condition = context.registerFile.getValue(operands[2])
        context.updateRegister(operands[0], if (condition != 0) value else 0)
    },
)
