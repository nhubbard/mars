package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMfc1 : BasicInstruction(
    "mfc1 \$t1,\$f1",
    "Move from Coprocessor 1 (FPU) : Set \$t1 to value in Coprocessor 1 register \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 00000 fffff sssss 00000 000000",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], context.coprocessor1.getValue(operands[1]))
    },
)
