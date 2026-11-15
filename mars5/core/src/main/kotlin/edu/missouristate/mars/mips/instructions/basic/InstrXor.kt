package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrXor : BasicInstruction(
    $$"xor $t1,$t2,$t3",
    $$"Bitwise XOR (exclusive OR) : Set $t1 to bitwise XOR of $t2 and $t3",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 100110",
    { statement, context ->
        val operands = statement.operands
        val left = context.readIntegerOperand(operands[1])
        val right = context.readIntegerOperand(operands[2])
        context.updateLongRegister(operands[0], left xor right)
    },
)
