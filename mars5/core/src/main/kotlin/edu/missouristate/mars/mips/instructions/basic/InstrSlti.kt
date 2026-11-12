package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSlti : BasicInstruction(
    "slti \$t1,\$t2,-100",
    "Set less than immediate : If \$t2 is less than sign-extended 16-bit immediate, then set \$t1"
        + " to 1 else set \$t1 to 0",
    BasicInstructionFormat.I_FORMAT,
    "001010 sssss fffff tttttttttttttttt",
    { statement, context ->
        val operands = statement.operands
        val value = context.registerFile.getValue(operands[1])
        val immediate = operands[2].toShort().toInt()
        context.updateRegister(operands[0], if (value < immediate) 1 else 0)
    },
)
