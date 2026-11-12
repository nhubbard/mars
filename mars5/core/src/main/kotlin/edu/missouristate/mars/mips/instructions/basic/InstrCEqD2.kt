package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCEqD2 : BasicInstruction(
    "c.eq.d 1,\$f2,\$f4",
    "Compare equal double precision : If \$f2 is equal to \$f4 (double-precision), set"
        + " Coprocessor 1 condition flag specified by immediate to true else set it to false",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 ttttt sssss fff 00 110010",
    { statement, context ->
        val operands = statement.operands
        if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
            throw ProcessingException(context, statement, "both registers must be even-numbered")
        }
        val left = context.readDoubleOperand(operands[1])
        val right = context.readDoubleOperand(operands[2])
        if (left == right) {
            context.setConditionFlag(operands[0])
        } else {
            context.clearConditionFlag(operands[0])
        }
    },
)
