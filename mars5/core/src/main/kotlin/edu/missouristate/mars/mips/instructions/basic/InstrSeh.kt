package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

class InstrSeh : BasicInstruction(
    $$"seh $t1,$t2",
    "Sign-extend the low halfword",
    BasicInstructionFormat.R_FORMAT,
    "011111 00000 sssss fffff 11000 100000",
    { statement, context ->
        val operands = statement.operands
        val value = context.registerFile.getValue(operands[1])
        context.updateRegister(operands[0], value.toShort().toInt())
    },
)
