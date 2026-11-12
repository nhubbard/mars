package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrBgezal : BasicInstruction(
    "bgezal \$t1,label",
    "Branch if greater then or equal to zero and link : If \$t1 is greater than or equal to"
        + " zero, then set \$ra to the Program Counter and branch to statement at label's"
        + " address",
    BasicInstructionFormat.I_BRANCH_FORMAT,
    "000001 fffff 10001 ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        if (context.registerFile.getValue(operands[0]) >= 0) {
            context.linkReturnAddress(31)
            context.branch(operands[1])
        }
    },
)
