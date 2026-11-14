package edu.missouristate.mars.mips.instructions.basic

class InstrBc : CompactBranchInstruction(
    "bc label",
    "Branch immediately to a PC-relative label",
    "110010 ffffffffffffffffffffffffff",
    { statement, context ->
        val operands = statement.operands
        val target = context.registerFile.programCounterBits + (operands[0] shl 2)
        context.compactTransfer(statement, target, branch = true)
    },
)
