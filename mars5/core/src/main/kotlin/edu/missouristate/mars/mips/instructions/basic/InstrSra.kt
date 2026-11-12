package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrSra : BasicInstruction(
    "sra \$t1,\$t2,10",
    "Shift right arithmetic : Set \$t1 to result of sign-extended shifting \$t2 right by number"
        + " of bits specified by immediate",
    BasicInstructionFormat.R_FORMAT,
    "000000 00000 sssss fffff ttttt 000011",
    { statement, context ->
        val operands = statement.operands
        context.updateRegister(operands[0], context.registerFile.getValue(operands[1]) shr operands[2])
    },
)
