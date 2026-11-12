package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMfhi : BasicInstruction(
    "mfhi \$t1",
    "Move from HI register : Set \$t1 to contents of HI (see multiply and divide operations)",
    BasicInstructionFormat.R_FORMAT,
    "000000 00000 00000 fffff 00000 010000",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], context.registerFile.getValue(33))
    },
)
