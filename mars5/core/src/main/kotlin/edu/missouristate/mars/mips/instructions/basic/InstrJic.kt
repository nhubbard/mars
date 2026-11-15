package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT

class InstrJic : BasicInstruction(
    $$"jic $t2,-100",
    "Jump immediately to a register address plus a signed offset",
    I_FORMAT,
    "110110 00000 fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        val target = context.registerJumpTarget(statement, operands[0], operands[1].toShort().toInt())
        context.compactTransfer(statement, target)
    },
)
