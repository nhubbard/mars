package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCLtD1 : BasicInstruction(
    "c.lt.d \$f2,\$f4",
    "Compare less than double precision : If \$f2 is less than \$f4 (double-precision), set"
        + " Coprocessor 1 condition flag 0 true else set it false",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 sssss fffff 00000 111100",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
            throw ProcessingException(context, statement, "both registers must be even-numbered")
        }
        val left = context.readDoubleOperand(operands[0])
        val right = context.readDoubleOperand(operands[1])
        if (left < right) {
            context.setConditionFlag(0)
        } else {
            context.clearConditionFlag(0)
        }
    },
)
