package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION

open class InstrTltu : BasicInstruction(
    "tltu \$t1,\$t2",
    "Trap if less than unsigned : Trap if \$t1 less than \$t2, unsigned comparison",
    BasicInstructionFormat.R_FORMAT,
    "000000 fffff sssss ccccc ccccc 110011",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[0]).toUInt()
        val right = context.registerFile.getValue(operands[1]).toUInt()
        if (left < right) {
            throw ProcessingException(context, statement, "trap", TRAP_EXCEPTION)
        }
    },
)
