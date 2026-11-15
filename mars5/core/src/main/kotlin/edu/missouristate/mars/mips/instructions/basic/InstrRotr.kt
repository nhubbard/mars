package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import java.lang.Integer.rotateRight

class InstrRotr : BasicInstruction(
    $$"rotr $t1,$t2,10",
    "Rotate a word right by an immediate",
    R_FORMAT,
    "000000 00001 sssss fffff ttttt 000010",
    { statement, context ->
        val operands = statement.operands
        val source = context.registerFile.getValue(operands[1])
        context.updateRegister(operands[0], rotateRight(source, operands[2]))
    },
)
