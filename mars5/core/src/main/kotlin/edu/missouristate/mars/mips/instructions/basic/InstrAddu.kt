package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrAddu : BasicInstruction(
    "addu \$t1,\$t2,\$t3",
    "Addition unsigned without overflow : set \$t1 to (\$t2 plus \$t3), no overflow",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 100001",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], context.registerFile.getValue(operands[1]) + context.registerFile.getValue(operands[2]))
    },
)
