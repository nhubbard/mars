package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMfc0 : BasicInstruction(
    "mfc0 \$t1,\$8",
    "Move from Coprocessor 0 : Set \$t1 to the value stored in Coprocessor 0 register \$8",
    BasicInstructionFormat.R_FORMAT,
    "010000 00000 fffff sssss 00000 000000",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], context.coprocessor0.getValue(operands[1]))
    },
)
