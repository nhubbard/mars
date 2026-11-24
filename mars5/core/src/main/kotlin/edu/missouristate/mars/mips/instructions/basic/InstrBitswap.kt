package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import java.lang.Integer.reverse
import java.lang.Integer.reverseBytes

class InstrBitswap :
    BasicInstruction(
        $$"bitswap $t1,$t2",
        "Reverse the bits within each byte",
        BasicInstructionFormat.R_FORMAT,
        "011111 00000 sssss fffff 00000 100000",
        { statement, context ->
          val operands = statement.operands
          val value = context.registerFile.getValue(operands[1])
          context.updateRegister(operands[0], reverse(reverseBytes(value)))
        },
    )
