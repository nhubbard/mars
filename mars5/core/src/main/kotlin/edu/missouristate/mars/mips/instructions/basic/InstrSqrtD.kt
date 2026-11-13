package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import kotlin.math.sqrt

open class InstrSqrtD : BasicInstruction(
    "sqrt.d \$f2,\$f4",
    "Square root double precision : Set \$f2 to double-precision floating point square root of"
        + " \$f4",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 00000 sssss fffff 000100",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
            throw ProcessingException(context, statement, "both registers must be even-numbered")
        }
        val value = context.readDoubleOperand(operands[1])
        context.writeDoubleResult(operands[0], if (value < 0.0) Double.NaN else sqrt(value))
    },
)
