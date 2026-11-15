package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION

open class InstrTgeiu : BasicInstruction(
    $$"tgeiu $t1,-100",
    $$"Trap if greater or equal to immediate unsigned : Trap if $t1 greater than or equal to"
        + " sign-extended 16 bit immediate, unsigned comparison",
    BasicInstructionFormat.I_FORMAT,
    "000001 fffff 01001 ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        val left = context.readIntegerOperand(operands[0]).toULong()
        val right = operands[1].toShort().toLong().toULong()
        if (left >= right) {
            throw ProcessingException(context, statement, "trap", TRAP_EXCEPTION)
        }
    },
)
