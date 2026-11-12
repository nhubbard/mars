package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovD : BasicInstruction(
    "mov.d \$f2,\$f4",
    "Move floating point double precision : Set double precision \$f2 to double precision value"
        + " in \$f4",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 00000 sssss fffff 000110",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
            throw ProcessingException(context, statement, "both registers must be even-numbered")
        }
        context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]))
        context.updateCoprocessor1(operands[0] + 1, context.coprocessor1.getValue(operands[1] + 1))
    },
)
