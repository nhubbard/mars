package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBgtz extends BasicInstruction {
  public InstrBgtz() {
    super(
        "bgtz $t1,label",
        "Branch if greater than zero : Branch to statement at label's address if $t1 is greater"
            + " than zero",
        BasicInstructionFormat.I_BRANCH_FORMAT,
        "000111 fffff 00000 ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[0]) > 0) {
            InstrHelpers.processBranch(context, operands[1]);
          }
        });
  }
}
