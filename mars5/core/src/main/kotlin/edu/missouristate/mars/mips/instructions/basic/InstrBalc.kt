package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.Instruction.Companion.INSTRUCTION_LENGTH

class InstrBalc : CompactBranchInstruction(
    "balc label",
    "Call a PC-relative label without a delay slot",
    "111010 ffffffffffffffffffffffffff",
    { statement, context ->
        val operands = statement.operands
        val target = context.registerFile.programCounter + (operands[0] shl 2)
        context.compactTransfer(statement, target, branch = true)
        context.link(31, statement.address + INSTRUCTION_LENGTH)
    },
)
