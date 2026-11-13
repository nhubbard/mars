package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat
import kotlin.math.sqrt

open class InstrSqrtS : BasicInstruction(
    "sqrt.s \$f0,\$f1",
    "Square root single precision : Set \$f0 to single-precision floating point square root of"
        + " \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 000100",
    { statement, context ->
        val operands = statement.operands
        val value = Float.fromBits(context.coprocessor1.getValue(operands[1]))
        val result = if (value < 0.0f) Float.NaN else sqrt(value.toDouble()).toFloat()
        context.updateCoprocessor1(operands[0], result.toBits())
    },
)
