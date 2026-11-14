package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrBeq : BasicInstruction(
    "beq \$t1,\$t2,label",
    "Branch if equal : Branch to statement at label's address if \$t1 and \$t2 are equal",
    BasicInstructionFormat.I_BRANCH_FORMAT,
    "000100 fffff sssss tttttttttttttttt",
    { statement, context ->
        val operands = statement.operands
        context.branch(operands[2], taken = context.readIntegerOperand(operands[0]) == context.readIntegerOperand(operands[1]))
    },
)
