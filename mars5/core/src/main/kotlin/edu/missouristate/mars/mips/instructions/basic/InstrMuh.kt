package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrMuh :
    BasicInstruction(
        $$"muh $t1,$t2,$t3",
        "Multiply signed words and keep the high word",
        R_FORMAT,
        "000000 sssss ttttt fffff 00011 011000",
        { statement, context ->
          val (destination, leftRegister, rightRegister) = statement.operands
          val left = context.registerFile.getValue(leftRegister)
          val right = context.registerFile.getValue(rightRegister)
          context.updateRegister(destination, ((left.toLong() * right) shr Int.SIZE_BITS).toInt())
        },
    )
