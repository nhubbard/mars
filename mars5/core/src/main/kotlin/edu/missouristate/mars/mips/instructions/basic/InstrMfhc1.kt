package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrMfhc1 : BasicInstruction(
    "mfhc1 \$t1,\$f4",
    "Copy the high word of a floating-point register pair to a CPU register",
    R_FORMAT,
    "010001 00011 fffff sssss 00000000000",
    { statement, context ->
        val (destination, source) = statement.operands
        if (source % 2 != 0) throw ProcessingException(context, statement, "FPU register must be even-numbered")
        context.updateRegister(destination, context.coprocessor1.getValue(source + 1))
    },
)
