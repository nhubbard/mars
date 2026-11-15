package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMovfD2 : BasicInstruction(
    $$"movf.d $f2,$f4,1",
    "Move floating point double precision : If condition flag specified by immediate is false,"
        + $$" set double precision $f2 to double precision value in $f4",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 ttt 00 sssss fffff 010001",
    { statement, context ->
        val operands = statement.operands
        if (!context.coprocessor1.hasWideRegisters && (operands[0] % 2 == 1 || operands[1] % 2 == 1)) {
            throw ProcessingException(context, statement, "both registers must be even-numbered")
        }
        if (context.coprocessor1.getConditionFlag(operands[2]) == 0) {
            context.copyDoubleword(operands[0], operands[1])
        }
    },
)
