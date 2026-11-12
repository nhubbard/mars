package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrBgez : BasicInstruction(
    "bgez \$t1,label",
    "Branch if greater than or equal to zero : Branch to statement at label's address if \$t1 is"
        + " greater than or equal to zero",
    BasicInstructionFormat.I_BRANCH_FORMAT,
    "000001 fffff 00001 ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        if (context.registerFile.getValue(operands[0]) >= 0) {
            context.branch(operands[1])
        }
    },
)
