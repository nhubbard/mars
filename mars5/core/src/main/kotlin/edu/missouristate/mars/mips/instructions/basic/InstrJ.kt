package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrJ : BasicInstruction(
    "j target",
    "Jump unconditionally : Jump to statement at target address",
    BasicInstructionFormat.J_FORMAT,
    "000010 ffffffffffffffffffffffffff",
    { statement, context ->
        val operands = statement.operands
        val region = context.registerFile.programCounter and 0xf0000000.toInt()
        context.jump(region or (operands[0] shl 2))
    },
)
