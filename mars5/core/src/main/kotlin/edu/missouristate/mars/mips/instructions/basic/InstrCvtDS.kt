package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCvtDS : BasicInstruction(
    "cvt.d.s \$f2,\$f1",
    "Convert from single precision to double precision : Set \$f2 to double precision equivalent"
        + " of single precision value in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 100001",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1) {
            throw ProcessingException(context, statement, "first register must be even-numbered")
        }
        context.writeDoubleResult(operands[0], Float.fromBits(context.coprocessor1.getValue(operands[1])).toDouble())
    },
)
