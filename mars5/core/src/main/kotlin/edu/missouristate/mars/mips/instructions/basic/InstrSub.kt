package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION

open class InstrSub : BasicInstruction(
    "sub \$t1,\$t2,\$t3",
    "Subtraction with overflow : set \$t1 to (\$t2 minus \$t3)",
    BasicInstructionFormat.R_FORMAT,
    "000000 sssss ttttt fffff 00000 100010",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1])
        val right = context.registerFile.getValue(operands[2])
        val result = left - right
        if ((left >= 0 && right < 0 && result < 0) || (left < 0 && right >= 0 && result >= 0)) {
            throw ProcessingException(context, statement, "arithmetic overflow", ARITHMETIC_OVERFLOW_EXCEPTION)
        }
        context.updateRegister(operands[0], result)
    },
)
