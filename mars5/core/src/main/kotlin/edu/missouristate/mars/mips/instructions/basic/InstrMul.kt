package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMul : BasicInstruction(
    "mul \$t1,\$t2,\$t3",
    "Multiplication without overflow  : Set HI to high-order 32 bits, LO and \$t1 to low-order"
        + " 32 bits of the product of \$t2 and \$t3 (use mfhi to access HI, mflo to access LO)",
    BasicInstructionFormat.R_FORMAT,
    "011100 sssss ttttt fffff 00000 000010",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1]).toLong()
        val right = context.registerFile.getValue(operands[2]).toLong()
        val product = left * right
        context.updateRegister(operands[0], product.toInt())
        context.updateRegister(33, (product shr 32).toInt())
        context.updateRegister(34, product.toInt())
    },
)
