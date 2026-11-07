package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMult extends BasicInstruction {
  public InstrMult() {
    super(
        "mult $t1,$t2",
        "Multiplication : Set hi to high-order 32 bits, lo to low-order 32 bits of the product of"
            + " $t1 and $t2 (use mfhi to access hi, mflo to access lo)",
        BasicInstructionFormat.R_FORMAT,
        "000000 fffff sssss 00000 00000 011000",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          long product =
              (long) context.registerFile.getValue(operands[0])
                  * (long) context.registerFile.getValue(operands[1]);

          context.updateRegister(33, (int) (product >> 32));
          context.updateRegister(34, (int) ((product << 32) >> 32));
        });
  }
}
