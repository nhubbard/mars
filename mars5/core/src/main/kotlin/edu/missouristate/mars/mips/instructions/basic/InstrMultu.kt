package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMultu : BasicInstruction(
    "multu \$t1,\$t2",
    "Multiplication unsigned : Set HI to high-order 32 bits, LO to low-order 32 bits of the"
        + " product of unsigned \$t1 and \$t2 (use mfhi to access HI, mflo to access LO)",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff sssss 00000 00000 011001",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[0]).toUInt().toLong()
        val right = context.registerFile.getValue(operands[1]).toUInt().toLong()
        val product = left * right
        context.updateRegister(33, (product shr 32).toInt())
        context.updateRegister(34, product.toInt())
    },
)
