package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrMuhu : BasicInstruction(
    "muhu \$t1,\$t2,\$t3",
    "Multiply unsigned words and keep the high word",
    R_FORMAT,
    "000000 sssss ttttt fffff 00011 011001",
    { statement, context ->
        val (destination, leftRegister, rightRegister) = statement.operands
        val left = context.registerFile.getValue(leftRegister)
        val right = context.registerFile.getValue(rightRegister)
        context.updateRegister(destination, ((left.toUInt().toULong() * right.toUInt().toULong()) shr Int.SIZE_BITS).toInt())
    },
)
