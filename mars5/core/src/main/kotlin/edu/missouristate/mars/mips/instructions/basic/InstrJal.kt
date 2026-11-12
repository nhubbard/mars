package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrJal : BasicInstruction(
    "jal target",
    "Jump and link : Set \$ra to Program Counter (return address) then jump to statement at"
        + " target address",
    BasicInstructionFormat.J_FORMAT,
    "000011 ffffffffffffffffffffffffff",
    { statement, context ->
        val operands = statement.operands
        context.linkReturnAddress(31)
        val region = context.registerFile.getProgramCounter() and 0xf0000000.toInt()
        context.jump(region or (operands[0] shl 2))
    },
)
