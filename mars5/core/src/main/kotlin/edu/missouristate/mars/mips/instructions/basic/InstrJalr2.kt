package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrJalr2 : BasicInstruction(
    "jalr \$t1",
    "Jump and link register : Set \$ra to Program Counter (return address) then jump to"
        + " statement whose address is in \$t1",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff 00000 11111 00000 001001",
    { statement, context ->
        val operands = statement.operands
        context.linkReturnAddress(31)
        context.jump(context.registerFile.getValue(operands[0]))
    },
)
