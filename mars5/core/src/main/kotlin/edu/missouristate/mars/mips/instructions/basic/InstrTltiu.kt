package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION

open class InstrTltiu : BasicInstruction(
    "tltiu \$t1,-100",
    "Trap if less than immediate unsigned : Trap if \$t1 less than sign-extended 16-bit"
        + " immediate, unsigned comparison",
    BasicInstructionFormat.I_FORMAT,
    "000001 fffff 01011 ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        val left = context.readIntegerOperand(operands[0]).toULong()
        val right = operands[1].toShort().toLong().toULong()
        if (left < right) {
            throw ProcessingException(context, statement, "trap", TRAP_EXCEPTION)
        }
    },
)
