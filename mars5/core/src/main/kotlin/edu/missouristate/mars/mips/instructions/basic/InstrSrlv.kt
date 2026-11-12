package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSrlv : BasicInstruction(
    "srlv \$t1,\$t2,\$t3",
    "Shift right logical variable : Set \$t1 to result of shifting \$t2 right by number of bits"
        + " specified by value in low-order 5 bits of \$t3",
    BasicInstructionFormat.R_FORMAT,
    "000000 ttttt sssss fffff 00000 000110",
    { statement, context ->
        val operands = statement.operands
        val value = context.registerFile.getValue(operands[1])
        val shift = context.registerFile.getValue(operands[2]) and 0x1f
        context.updateRegister(operands[0], value ushr shift)
    },
)
