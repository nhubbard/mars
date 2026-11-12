package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSltiu : BasicInstruction(
    "sltiu \$t1,\$t2,-100",
    "Set less than immediate unsigned : If \$t2 is less than  sign-extended 16-bit immediate"
        + " using unsigned comparison, then set \$t1 to 1 else set \$t1 to 0",
    BasicInstructionFormat.I_FORMAT,
    "001011 sssss fffff tttttttttttttttt",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1]).toUInt()
        val right = operands[2].toShort().toInt().toUInt()
        context.updateRegister(operands[0], if (left < right) 1 else 0)
    },
)
