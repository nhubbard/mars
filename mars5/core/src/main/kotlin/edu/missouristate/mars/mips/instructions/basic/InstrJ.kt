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
        val region = context.registerFile.programCounterBits and -0x10000000L
        context.jump(region or ((operands[0] and 0x03ff_ffff).toLong() shl 2))
    },
)
