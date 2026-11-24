package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import java.lang.Integer.rotateRight

class InstrRotrv :
    BasicInstruction(
        $$"rotrv $t1,$t2,$t3",
        "Rotate a word right by a register count",
        R_FORMAT,
        "000000 ttttt sssss fffff 00001 000110",
        { statement, context ->
          val operands = statement.operands
          val source = context.registerFile.getValue(operands[1])
          context.updateRegister(
              operands[0],
              rotateRight(source, context.registerFile.getValue(operands[2])),
          )
        },
    )
