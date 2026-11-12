package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.BREAKPOINT_EXCEPTION

open class InstrBreak2 : BasicInstruction(
    "break",
    "Break execution : Terminate program execution with exception",
    BasicInstructionFormat.R_FORMAT,
    "000000 00000 00000 00000 00000 001101",
    { statement, context ->
        throw ProcessingException(
            context, statement, "break instruction executed; no code given.", BREAKPOINT_EXCEPTION,
        )
    },
)
