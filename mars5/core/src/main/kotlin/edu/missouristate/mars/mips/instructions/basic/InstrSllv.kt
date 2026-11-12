package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSllv : BasicInstruction(
    "sllv \$t1,\$t2,\$t3",
    "Shift left logical variable : Set \$t1 to result of shifting \$t2 left by number of bits"
        + " specified by value in low-order 5 bits of \$t3",
    BasicInstructionFormat.R_FORMAT,
    "000000 ttttt sssss fffff 00000 000100",
    { statement, context ->
        val operands = statement.operands
        val value = context.registerFile.getValue(operands[1])
        val shift = context.registerFile.getValue(operands[2]) and 0x1f
        context.updateRegister(operands[0], value shl shift)
    },
)
