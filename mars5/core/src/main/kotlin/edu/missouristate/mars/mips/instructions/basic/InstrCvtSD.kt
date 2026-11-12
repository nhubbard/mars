package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCvtSD : BasicInstruction(
    "cvt.s.d \$f1,\$f2",
    "Convert from double precision to single precision : Set \$f1 to single precision equivalent"
        + " of double precision value in \$f2",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 00000 sssss fffff 100000",
    { statement, context ->
        val operands = statement.operands
        if (operands[1] % 2 == 1) {
            throw ProcessingException(context, statement, "second register must be even-numbered")
        }
        context.updateCoprocessor1(operands[0], context.readDoubleOperand(operands[1]).toFloat().toBits())
    },
)
