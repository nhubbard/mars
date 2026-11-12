package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrNop : BasicInstruction(
    "nop",
    "Null operation : machine code is all zeroes",
    BasicInstructionFormat.R_FORMAT,
    "000000 00000 00000 00000 00000 000000",
    { _, _ -> },
)
