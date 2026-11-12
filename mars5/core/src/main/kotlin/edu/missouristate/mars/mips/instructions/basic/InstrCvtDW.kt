package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCvtDW : BasicInstruction(
    "cvt.d.w \$f2,\$f1",
    "Convert from word to double precision : Set \$f2 to double precision equivalent of 32-bit"
        + " integer value in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10100 00000 sssss fffff 100001",
    { statement, context ->
        val operands = statement.operands
        if (operands[0] % 2 == 1) {
            throw ProcessingException(context, statement, "first register must be even-numbered")
        }
        context.writeDoubleResult(operands[0], context.coprocessor1.getValue(operands[1]).toDouble())
    },
)
