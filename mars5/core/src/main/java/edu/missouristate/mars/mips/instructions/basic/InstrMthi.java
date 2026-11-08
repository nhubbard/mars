package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMthi extends BasicInstruction {
  public InstrMthi() {
    super(
        "mthi $t1",
        "Move to HI registerr : Set HI to contents of $t1 (see multiply and divide operations)",
        BasicInstructionFormat.R_FORMAT,
        "000000 fffff 00000 00000 00000 010001",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          context.updateRegister(33, context.registerFile.getValue(operands[0]));
        });
  }
}
