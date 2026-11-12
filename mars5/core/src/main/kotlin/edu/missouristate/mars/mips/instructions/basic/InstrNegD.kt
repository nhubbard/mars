package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrNegD : BasicInstruction(
    "neg.d \$f2,\$f4",
    "Floating point negate double precision : Set double precision \$f2 to negation of double"
        + " precision value in \$f4",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 00000 sssss fffff 000111",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
            throw ProcessingException(context, statement, "both registers must be even-numbered")
        }
        val high = context.coprocessor1.getValue(operands[1] + 1)
        context.updateCoprocessor1(operands[0] + 1, high xor Int.MIN_VALUE)
        context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]))
    },
)
