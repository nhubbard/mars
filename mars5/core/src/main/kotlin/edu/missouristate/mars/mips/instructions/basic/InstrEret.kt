package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.hardware.Coprocessor0State.EXCEPTION_LEVEL
import edu.missouristate.mars.mips.hardware.Coprocessor0State.STATUS
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.util.Binary

open class InstrEret : BasicInstruction(
    "eret",
    "Exception return : Set Program Counter to Coprocessor 0 EPC register value, set"
        + " Coprocessor Status register bit 1 (exception level) to zero",
    BasicInstructionFormat.R_FORMAT,
    "010000 1 0000000000000000000 011000",
    { _, context ->
        val status = context.coprocessor0.getValue(STATUS)
        context.updateCoprocessor0(STATUS, Binary.clearBit(status, EXCEPTION_LEVEL))
        context.returnFromException()
    },
)
