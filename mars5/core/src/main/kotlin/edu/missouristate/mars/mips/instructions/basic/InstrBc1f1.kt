package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrBc1f1 : BasicInstruction(
    "bc1f label",
    "Branch if FP condition flag 0 false (BC1F, not BCLF) : If Coprocessor 1 condition flag 0"
        + " is false (zero) then branch to statement at label's address",
    BasicInstructionFormat.I_BRANCH_FORMAT,
    "010001 01000 00000 ffffffffffffffff",
    { statement, context ->
        val operands = statement.operands
        if (context.coprocessor1.getConditionFlag(0) == 0) {
            context.branch(operands[0])
        }
    },
)
