package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrClassS : BasicInstruction(
    $$"class.s $f2,$f4",
    "Classify a single-precision value by its bit pattern",
    R_FORMAT,
    "010001 10000 00000 sssss fffff 011011",
    { statement, context ->
        val (destination, source) = statement.operands
        val bits = context.coprocessor1.getValue(source).toUInt().toLong()
        context.updateCoprocessor1(destination, floatingClass(bits, 23, 8))
    },
)
