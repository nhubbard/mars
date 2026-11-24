package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrMthc1 :
    BasicInstruction(
        $$"mthc1 $t1,$f4",
        "Copy a CPU word to the high half of a floating-point register pair",
        R_FORMAT,
        "010001 00111 fffff sssss 00000000000",
        { statement, context ->
          val (source, destination) = statement.operands
          val fpu = context.coprocessor1
          val high = context.registerFile.getValue(source)
          if (fpu.hasWideRegisters) {
            val low = fpu.getLongFromRegisterPair(destination).toUInt().toLong()
            context.updateCoprocessor1Doubleword(
                destination,
                (high.toLong() shl Int.SIZE_BITS) or low,
            )
          } else {
            if (destination % 2 != 0)
                throw ProcessingException(context, statement, "FPU register must be even-numbered")
            context.updateCoprocessor1(destination + 1, high)
          }
        },
    )
