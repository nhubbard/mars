package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMultu extends BasicInstruction {
  public InstrMultu() {
    super(
        "multu $t1,$t2",
        "Multiplication unsigned : Set HI to high-order 32 bits, LO to low-order 32 bits of the"
            + " product of unsigned $t1 and $t2 (use mfhi to access HI, mflo to access LO)",
        BasicInstructionFormat.R_FORMAT,
        "000000 fffff sssss 00000 00000 011001",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          long product =
              (((long) context.registerFile.getValue(operands[0])) << 32 >>> 32)
                  * (((long) context.registerFile.getValue(operands[1])) << 32 >>> 32);

          context.updateRegister(33, (int) (product >> 32));
          context.updateRegister(34, (int) ((product << 32) >> 32));
        });
  }
}
