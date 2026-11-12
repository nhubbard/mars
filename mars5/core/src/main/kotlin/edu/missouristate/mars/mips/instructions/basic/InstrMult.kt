package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMult : BasicInstruction(
    "mult \$t1,\$t2",
    "Multiplication : Set hi to high-order 32 bits, lo to low-order 32 bits of the product of"
        + " \$t1 and \$t2 (use mfhi to access hi, mflo to access lo)",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff sssss 00000 00000 011000",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[0]).toLong()
        val right = context.registerFile.getValue(operands[1]).toLong()
        val product = left * right
        context.updateRegister(33, (product shr 32).toInt())
        context.updateRegister(34, product.toInt())
    },
)
