package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMtc1 : BasicInstruction(
    "mtc1 \$t1,\$f1",
    "Move to Coprocessor 1 (FPU) : Set Coprocessor 1 register \$f1 to value in \$t1",
    BasicInstructionFormat.R_FORMAT,
    "010001 00100 fffff sssss 00000 000000",
    { statement, context ->
        val operands = statement.operands
        context.updateCoprocessor1(operands[1], context.registerFile.getValue(operands[0]))
    },
)
