package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSltu : BasicInstruction(
    "sltu \$t1,\$t2,\$t3",
    "Set less than unsigned : If \$t2 is less than \$t3 using unsigned comparision, then set \$t1"
        + " to 1 else set \$t1 to 0",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 101011",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1]).toUInt()
        val right = context.registerFile.getValue(operands[2]).toUInt()
        context.updateRegister(operands[0], if (left < right) 1 else 0)
    },
)
