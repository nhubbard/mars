package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.TRAP_EXCEPTION

open class InstrTeqi : BasicInstruction(
    "teqi \$t1,-100",
    "Trap if equal to immediate : Trap if \$t1 is equal to sign-extended 16 bit immediate",
    BasicInstructionFormat.I_FORMAT,
    "000001 fffff 01100 ssssssssssssssss",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[0])
        val right = operands[1].toShort().toInt()
        if (left == right) {
            throw ProcessingException(context, statement, "trap", TRAP_EXCEPTION)
        }
    },
)
