package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrDiv : BasicInstruction(
    "div \$t1,\$t2",
    "Division with overflow : Divide \$t1 by \$t2 then set LO to quotient and HI to remainder"
        + " (use mfhi to access HI, mflo to access LO)",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff sssss 00000 00000 011010",
    { statement, context ->
        val operands = statement.operands
        if (context.registerFile.getValue(operands[1]) != 0) {
            context.updateRegister(33, context.registerFile.getValue(operands[0]) % context.registerFile.getValue(operands[1]))
            context.updateRegister(34, context.registerFile.getValue(operands[0]) / context.registerFile.getValue(operands[1]))
        }
    },
)
