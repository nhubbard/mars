package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION

open class InstrTeq : BasicInstruction(
    "teq \$t1,\$t2",
    "Trap if equal : Trap if \$t1 is equal to \$t2",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff sssss ccccc ccccc 110100",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[0])
        val right = context.registerFile.getValue(operands[1])
        if (left == right) {
            throw ProcessingException(context, statement, "trap", TRAP_EXCEPTION)
        }
    },
)
