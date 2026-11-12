package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCvtWS : BasicInstruction(
    "cvt.w.s \$f0,\$f1",
    "Convert from single precision to word : Set \$f0 to 32-bit integer equivalent of single"
        + " precision value in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 100100",
    { statement, context ->
        val operands = statement.operands
        context.updateCoprocessor1(operands[0], Float.fromBits(context.coprocessor1.getValue(operands[1])).toInt())
    },
)
