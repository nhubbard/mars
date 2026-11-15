package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

class InstrWsbh : BasicInstruction(
    $$"wsbh $t1,$t2",
    "Swap the two bytes in each halfword",
    BasicInstructionFormat.R_FORMAT,
    "011111 00000 sssss fffff 00010 100000",
    { statement, context ->
        val operands = statement.operands
        val value = context.registerFile.getValue(operands[1])
        context.updateRegister(operands[0], ((value and 0x00ff00ff) shl 8) or ((value ushr 8) and 0x00ff00ff))
    },
)
