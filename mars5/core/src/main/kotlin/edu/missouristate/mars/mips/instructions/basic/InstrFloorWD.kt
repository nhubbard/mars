package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import kotlin.math.floor

open class InstrFloorWD : BasicInstruction(
    "floor.w.d \$f1,\$f2",
    "Floor double precision to word : Set \$f1 to 32-bit integer floor of double-precision float"
        + " in \$f2",
    BasicInstructionFormat.R_FORMAT,
    "010001 10001 00000 sssss fffff 001111",
    { statement, context ->
        val operands = statement.operands
        if (operands[1] % 2 == 1) {
            throw ProcessingException(context, statement, "second register must be even-numbered")
        }
        val value = context.readDoubleOperand(operands[1])
        val result = if (!value.isFinite() || value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) {
            Int.MAX_VALUE
        } else {
            floor(value).toInt()
        }
        context.updateCoprocessor1(operands[0], result)
    },
)
