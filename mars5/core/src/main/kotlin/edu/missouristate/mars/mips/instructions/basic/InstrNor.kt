package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrNor : BasicInstruction(
    "nor \$t1,\$t2,\$t3",
    "Bitwise NOR : Set \$t1 to bitwise NOR of \$t2 and \$t3",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 100111",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1])
        val right = context.registerFile.getValue(operands[2])
        context.updateRegister(operands[0], (left or right).inv())
    },
)
