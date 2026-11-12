package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrAnd : BasicInstruction(
    "and \$t1,\$t2,\$t3",
    "Bitwise AND : Set \$t1 to bitwise AND of \$t2 and \$t3",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 100100",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1])
        val right = context.registerFile.getValue(operands[2])
        context.updateRegister(operands[0], left and right)
    },
)
