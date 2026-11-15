package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT

class InstrAui : BasicInstruction(
    $$"aui $t1,$t2,100",
    "Add an immediate shifted left by 16",
    I_FORMAT,
    "001111 sssss fffff tttttttttttttttt",
    { statement, context ->
        val operands = statement.operands
        val source = context.registerFile.getValue(operands[1])
        context.updateRegister(operands[0], source + (operands[2] shl 16))
    },
)
