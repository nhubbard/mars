package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.BREAKPOINT_EXCEPTION

open class InstrBreak1 : BasicInstruction(
    "break 100",
    "Break execution with code : Terminate program execution with specified exception code",
    BasicInstructionFormat.R_FORMAT,
    "000000 ffffffffffffffffffff 001101",
    { statement, context ->
        val operands = statement.operands
        throw ProcessingException(
            context, statement, "break instruction executed; code = ${operands[0]}.", BREAKPOINT_EXCEPTION,
        )
    },
)
