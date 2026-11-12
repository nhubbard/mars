package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMtc0 : BasicInstruction(
    "mtc0 \$t1,\$8",
    "Move to Coprocessor 0 : Set Coprocessor 0 register \$8 to value stored in \$t1",
    BasicInstructionFormat.R_FORMAT,
    "010000 00100 fffff sssss 00000 000000",
    { statement, context ->
        val operands = statement.operands
        context.updateCoprocessor0(operands[1], context.registerFile.getValue(operands[0]))
    },
)
