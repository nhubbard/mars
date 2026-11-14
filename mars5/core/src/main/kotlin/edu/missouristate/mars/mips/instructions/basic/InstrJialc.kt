package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.Instruction.Companion.INSTRUCTION_LENGTH

class InstrJialc : BasicInstruction(
    "jialc \$t2,-100",
    "Call a register address plus a signed offset without a delay slot",
    I_FORMAT,
    "111110 00000 fffff ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        val target = context.registerJumpTarget(statement, operands[0], operands[1].toShort().toInt())
        context.compactTransfer(statement, target)
        context.link(31, statement.addressBits + INSTRUCTION_LENGTH)
    },
)
