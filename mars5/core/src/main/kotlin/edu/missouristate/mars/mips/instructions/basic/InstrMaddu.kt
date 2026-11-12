package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.util.Binary

open class InstrMaddu : BasicInstruction(
    "maddu \$t1,\$t2",
    "Multiply add unsigned : Multiply \$t1 by \$t2 then increment HI by high-order 32 bits of"
        + " product, increment LO by low-order 32 bits of product, unsigned (use mfhi to access"
        + " HI, mflo to access LO)",
    BasicInstructionFormat.R_FORMAT,
    "011100 fffff sssss 00000 00000 000001",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[0]).toUInt().toLong()
        val right = context.registerFile.getValue(operands[1]).toUInt().toLong()
        val accumulator = Binary.twoIntsToLong(context.registerFile.getValue(33), context.registerFile.getValue(34))
        val result = accumulator + (left * right)
        context.updateRegister(33, Binary.highOrderLongToInt(result))
        context.updateRegister(34, Binary.lowOrderLongToInt(result))
    },
)
