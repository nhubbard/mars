package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrMfhc1 : BasicInstruction(
    $$"mfhc1 $t1,$f4",
    "Copy the high word of a floating-point register pair to a CPU register",
    R_FORMAT,
    "010001 00011 fffff sssss 00000000000",
    { statement, context ->
        val (destination, source) = statement.operands
        val fpu = context.coprocessor1
        val high = if (fpu.hasWideRegisters) {
            (fpu.getLongFromRegisterPair(source) ushr Int.SIZE_BITS).toInt()
        } else {
            if (source % 2 != 0) throw ProcessingException(context, statement, "FPU register must be even-numbered")
            fpu.getValue(source + 1)
        }
        context.updateRegister(destination, high)
    },
)
