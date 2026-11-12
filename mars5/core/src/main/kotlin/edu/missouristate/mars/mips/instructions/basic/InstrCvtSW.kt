package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrCvtSW : BasicInstruction(
    "cvt.s.w \$f0,\$f1",
    "Convert from word to single precision : Set \$f0 to single precision equivalent of 32-bit"
        + " integer value in \$f2",
    BasicInstructionFormat.R_FORMAT,
    "010001 10100 00000 sssss fffff 100000",
    { statement, context ->
        val operands = statement.operands
        context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]).toFloat().toBits())
    },
)
