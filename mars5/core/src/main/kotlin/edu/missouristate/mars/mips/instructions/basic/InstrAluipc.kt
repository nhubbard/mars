package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT

class InstrAluipc : BasicInstruction(
    "aluipc \$t1,-100",
    "Add an upper immediate to the instruction address and clear its low bits",
    I_FORMAT,
    "111011 fffff 11111 ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        context.updateLongRegister(operands[0], (statement.addressBits + (operands[1] shl 16)) and -0x10000L)
    },
)
