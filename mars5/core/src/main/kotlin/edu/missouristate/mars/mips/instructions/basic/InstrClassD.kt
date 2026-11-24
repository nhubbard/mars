package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

class InstrClassD :
    BasicInstruction(
        $$"class.d $f2,$f4",
        "Classify a double-precision register pair by its bit pattern",
        R_FORMAT,
        "010001 10001 00000 sssss fffff 011011",
        { statement, context ->
          val (destination, source) = statement.operands
          if (!context.coprocessor1.hasWideRegisters && (destination % 2 != 0 || source % 2 != 0)) {
            throw ProcessingException(context, statement, "both registers must be even-numbered")
          }
          val bits = context.coprocessor1.getLongFromRegisterPair(source)
          context.writeDoubleBits(destination, floatingClass(bits, 52, 11).toLong())
        },
    )
