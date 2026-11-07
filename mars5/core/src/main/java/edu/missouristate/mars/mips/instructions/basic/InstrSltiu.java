package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSltiu extends BasicInstruction {
  public InstrSltiu() {
    super(
        "sltiu $t1,$t2,-100",
        "Set less than immediate unsigned : If $t2 is less than  sign-extended 16-bit immediate"
            + " using unsigned comparison, then set $t1 to 1 else set $t1 to 0",
        BasicInstructionFormat.I_FORMAT,
        "001011 sssss fffff tttttttttttttttt",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          int first = context.registerFile.getValue(operands[1]);

          int second = operands[2] << 16 >> 16;
          if (first >= 0 && second >= 0 || first < 0 && second < 0) {
            context.updateRegister(operands[0], (first < second) ? 1 : 0);
          } else {
            context.updateRegister(operands[0], (first >= 0) ? 1 : 0);
          }
        });
  }
}
