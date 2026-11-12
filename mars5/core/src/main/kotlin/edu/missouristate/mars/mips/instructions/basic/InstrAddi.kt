package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import edu.missouristate.mars.simulator.Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION

open class InstrAddi : BasicInstruction(
    "addi \$t1,\$t2,-100",
    "Addition immediate with overflow : set \$t1 to (\$t2 plus signed 16-bit immediate)",
    BasicInstructionFormat.I_FORMAT,
    "001000 sssss fffff tttttttttttttttt",
    { statement, context ->
        val operands = statement.operands
        val left = context.registerFile.getValue(operands[1])
        val right = operands[2].toShort().toInt()
        val result = left + right
        if ((left >= 0 && right >= 0 && result < 0) || (left < 0 && right < 0 && result >= 0)) {
            throw ProcessingException(context, statement, "arithmetic overflow", ARITHMETIC_OVERFLOW_EXCEPTION)
        }
        context.updateRegister(operands[0], result)
    },
)
