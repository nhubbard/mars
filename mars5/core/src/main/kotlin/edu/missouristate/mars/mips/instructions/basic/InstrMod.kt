package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION

class InstrMod :
    BasicInstruction(
        $$"mod $t1,$t2,$t3",
        "Store the remainder of a signed division",
        R_FORMAT,
        "000000 sssss ttttt fffff 00011 011010",
        { statement, context ->
          val (destination, leftRegister, rightRegister) = statement.operands
          val left = context.registerFile.getValue(leftRegister)
          val right = context.registerFile.getValue(rightRegister)
          if (right == 0)
              throw ProcessingException(context, statement, "division by zero", TRAP_EXCEPTION)
          context.updateRegister(destination, left % right)
        },
    )
