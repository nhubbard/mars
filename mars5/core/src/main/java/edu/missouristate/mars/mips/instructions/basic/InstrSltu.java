package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSltu extends BasicInstruction {
  public InstrSltu() {
    super(
        "sltu $t1,$t2,$t3",
        "Set less than unsigned : If $t2 is less than $t3 using unsigned comparision, then set $t1"
            + " to 1 else set $t1 to 0",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttttt fffff 00000 101011",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          int first = context.registerFile.getValue(operands[1]);
          int second = context.registerFile.getValue(operands[2]);
          if (first >= 0 && second >= 0 || first < 0 && second < 0) {
            context.updateRegister(operands[0], (first < second) ? 1 : 0);
          } else {
            context.updateRegister(operands[0], (first >= 0) ? 1 : 0);
          }
        });
  }
}
