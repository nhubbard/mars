package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrOri : BasicInstruction(
    "ori \$t1,\$t2,100",
    "Bitwise OR immediate : Set \$t1 to bitwise OR of \$t2 and zero-extended 16-bit immediate",
    BasicInstructionFormat.I_FORMAT,
    "001101 sssss fffff tttttttttttttttt",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1])
        val immediate = operands[2] and 0xffff
        context.updateRegister(operands[0], left or immediate)
    },
)
