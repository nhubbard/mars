package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrClz : BasicInstruction(
    "clz \$t1,\$t2",
    "Count number of leading zeroes : Set \$t1 to the count of leading zero bits in \$t2 starting"
        + " at most significant bit positio",
    BasicInstructionFormat.R_FORMAT,
    "011100 sssss 00000 fffff 00000 100000",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], context.registerFile.getValue(operands[1]).countLeadingZeroBits())
    },
)
