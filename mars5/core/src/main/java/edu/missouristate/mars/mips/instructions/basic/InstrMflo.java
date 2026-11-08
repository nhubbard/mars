package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMflo extends BasicInstruction {
  public InstrMflo() {
    super(
        "mflo $t1",
        "Move from LO register : Set $t1 to contents of LO (see multiply and divide operations)",
        BasicInstructionFormat.R_FORMAT,
        "000000 00000 00000 fffff 00000 010010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          context.updateRegister(operands[0], context.registerFile.getValue(34));
        });
  }
}
