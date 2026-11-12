package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrJr : BasicInstruction(
    "jr \$t1",
    "Jump register unconditionally : Jump to statement whose address is in \$t1",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff 00000 00000 00000 001000",
    { statement, context ->
        val operands = statement.operands
        context.jump(context.registerFile.getValue(operands[0]))
    },
)
