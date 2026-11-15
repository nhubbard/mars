package edu.missouristate.mars.mips.instructions.basic


class InstrBalc : CompactBranchInstruction(
    "balc label",
    "Call a PC-relative label without a delay slot",
    "111010 ffffffffffffffffffffffffff",
    { statement, context ->
        val operands = statement.operands
        val target = context.registerFile.programCounterBits + (operands[0] shl 2)
        context.compactTransfer(statement, target, branch = true)
        context.link(31, statement.addressBits + INSTRUCTION_LENGTH)
    },
)
