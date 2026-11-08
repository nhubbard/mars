package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMovt2 extends BasicInstruction {
  public InstrMovt2() {
    super(
        "movt $t1,$t2,1",
        "Move if specfied FP condition flag true : Set $t1 to $t2 if FPU (Coprocessor 1) condition"
            + " flag specified by the immediate is true (one)",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttt 01 fffff 00000 000001",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.coprocessor1.getConditionFlag(operands[2]) == 1) {
            context.updateRegister(operands[0], context.registerFile.getValue(operands[1]));
          }
        });
  }
}
