package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrMulS : BasicInstruction(
    "mul.s \$f0,\$f1,\$f3",
    "Floating point multiplication single precision : Set \$f0 to single-precision floating"
        + " point value of \$f1 times \$f3",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 ttttt sssss fffff 000010",
    { statement, context ->
        val operands = statement.operands
        val left = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        val right = Float.fromBits(context.coprocessor1.getValue(operands[2]))
        context.updateCoprocessor1(operands[0], (left * right).toBits())
    },
)
