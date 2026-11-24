package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT

class InstrAuipc :
    BasicInstruction(
        $$"auipc $t1,-100",
        "Add an upper immediate to the instruction address",
        I_FORMAT,
        "111011 fffff 11110 ssssssssssssssss",
        { statement, context ->
          val operands = statement.operands
          context.updateLongRegister(operands[0], statement.addressBits + (operands[1] shl 16))
        },
    )
