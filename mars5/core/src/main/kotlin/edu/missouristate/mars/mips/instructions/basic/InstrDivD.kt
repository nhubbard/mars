package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrDivD : BasicInstruction(
    "div.d \$f2,\$f4,\$f6",
    "Floating point division double precision : Set \$f2 to double-precision floating point"
        + " value of \$f4 divided by \$f6",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 ttttt sssss fffff 000011",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
            throw ProcessingException(context, statement, "all registers must be even-numbered")
        }
        val left = context.readDoubleOperand(operands[1])
        val right = context.readDoubleOperand(operands[2])
        context.writeDoubleResult(operands[0], left / right)
    },
)
