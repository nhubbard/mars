package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat

open class InstrNegS : BasicInstruction(
    "neg.s \$f0,\$f1",
    "Floating point negate single precision : Set single precision \$f0 to negation of single"
        + " precision value in \$f1",
    BasicInstructionFormat.R_FORMAT,
    "010001 10000 00000 sssss fffff 000111",
    { statement, context ->
        val operands = statement.operands
        val bits = context.coprocessor1.getValue(operands[1])
        context.updateCoprocessor1(operands[0], bits xor Int.MIN_VALUE)
    },
)
