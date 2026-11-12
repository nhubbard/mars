package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovt1 : BasicInstruction(
    "movt \$t1,\$t2",
    "Move if FP condition flag 0 true : Set \$t1 to \$t2 if FPU (Coprocessor 1) condition flag 0"
        + " is true (one)",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss 000 01 fffff 00000 000001",
    { statement, context ->
        val operands = statement.operands
        if (context.coprocessor1.getConditionFlag(0) == 1) {
            context.updateRegister(operands[0], context.registerFile.getValue(operands[1]))
        }
    },
)
