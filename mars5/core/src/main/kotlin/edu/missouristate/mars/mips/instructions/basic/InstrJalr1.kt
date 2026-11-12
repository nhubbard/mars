package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrJalr1 : BasicInstruction(
    "jalr \$t1,\$t2",
    "Jump and link register : Set \$t1 to Program Counter (return address) then jump to"
        + " statement whose address is in \$t2",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss 00000 fffff 00000 001001",
    { statement, context ->
        val operands = statement.operands
        context.linkReturnAddress(operands[0])
        context.jump(context.registerFile.getValue(operands[1]))
    },
)
