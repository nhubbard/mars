package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

class InstrSeleqz : BasicInstruction(
    "seleqz \$t1,\$t2,\$t3",
    "Select a register value when the condition register is zero",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 110101",
    { statement, context ->
        val operands = statement.operands
        val value = context.readIntegerOperand(operands[1])
        val condition = context.readIntegerOperand(operands[2])
        context.updateLongRegister(operands[0], if (condition == 0L) value else 0)
    },
)
