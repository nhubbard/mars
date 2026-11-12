package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION

open class InstrTne : BasicInstruction(
    "tne \$t1,\$t2",
    "Trap if not equal : Trap if \$t1 is not equal to \$t2",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff sssss ccccc ccccc 110110",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[0])
        val right = context.registerFile.getValue(operands[1])
        if (left != right) {
            throw ProcessingException(context, statement, "trap", TRAP_EXCEPTION)
        }
    },
)
