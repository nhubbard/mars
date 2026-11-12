package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrBc1f2 : BasicInstruction(
    "bc1f 1,label",
    "Branch if specified FP condition flag false (BC1F, not BCLF) : If Coprocessor 1 condition"
        + " flag specified by immediate is false (zero) then branch to statement at label's"
        + " address",
    BasicInstructionFormat.I_BRANCH_FORMAT,
    "010001 01000 fff 00 ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        if (context.coprocessor1.getConditionFlag(operands[0]) == 0) {
            context.branch(operands[1])
        }
    },
)
