package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrDivu : BasicInstruction(
    "divu \$t1,\$t2",
    "Division unsigned without overflow : Divide unsigned \$t1 by \$t2 then set LO to quotient"
        + " and HI to remainder (use mfhi to access HI, mflo to access LO)",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff sssss 00000 00000 011011",
    { statement, context ->
        val operands = statement.operands
        if (context.registerFile.getValue(operands[1]) != 0) {
            val left = context.registerFile.getValue(operands[0]).toUInt()
            val right = context.registerFile.getValue(operands[1]).toUInt()
            context.updateRegister(33, (left % right).toInt())
            context.updateRegister(34, (left / right).toInt())
        }
    },
)
