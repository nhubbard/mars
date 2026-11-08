package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBgezal extends BasicInstruction {
  public InstrBgezal() {
    super(
        "bgezal $t1,label",
        "Branch if greater then or equal to zero and link : If $t1 is greater than or equal to"
            + " zero, then set $ra to the Program Counter and branch to statement at label's"
            + " address",
        BasicInstructionFormat.I_BRANCH_FORMAT,
        "000001 fffff 10001 ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[0]) >= 0) {
            InstrHelpers.processReturnAddress(context, 31);
            InstrHelpers.processBranch(context, operands[1]);
          }
        });
  }
}
