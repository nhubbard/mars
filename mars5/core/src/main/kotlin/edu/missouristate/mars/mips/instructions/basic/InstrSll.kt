package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSll : BasicInstruction(
    "sll \$t1,\$t2,10",
    "Shift left logical : Set \$t1 to result of shifting \$t2 left by number of bits specified by"
        + " immediate",
    BasicInstructionFormat.R_FORMAT,
    "000000 00000 sssss fffff ttttt 000000",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], context.registerFile.getValue(operands[1]) shl operands[2])
    },
)
