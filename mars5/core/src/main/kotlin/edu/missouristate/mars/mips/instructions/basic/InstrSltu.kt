package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSltu :
    BasicInstruction(
        $$"sltu $t1,$t2,$t3",
        $$"Set less than unsigned : If $t2 is less than $t3 using unsigned comparison, then set $t1" +
            $$" to 1 else set $t1 to 0",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttttt fffff 00000 101011",
        { statement, context ->
          val operands = statement.operands
          val left = context.readIntegerOperand(operands[1]).toULong()
          val right = context.readIntegerOperand(operands[2]).toULong()
          context.updateRegister(operands[0], if (left < right) 1 else 0)
        },
    )
