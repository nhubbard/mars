package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMul extends BasicInstruction {
  public InstrMul() {
    super(
        "mul $t1,$t2,$t3",
        "Multiplication without overflow  : Set HI to high-order 32 bits, LO and $t1 to low-order"
            + " 32 bits of the product of $t2 and $t3 (use mfhi to access HI, mflo to access LO)",
        BasicInstructionFormat.R_FORMAT,
        "011100 sssss ttttt fffff 00000 000010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          long product =
              (long) context.registerFile.getValue(operands[1])
                  * (long) context.registerFile.getValue(operands[2]);
          context.updateRegister(operands[0], (int) ((product << 32) >> 32));

          context.updateRegister(33, (int) (product >> 32));
          context.updateRegister(34, (int) ((product << 32) >> 32));
        });
  }
}
