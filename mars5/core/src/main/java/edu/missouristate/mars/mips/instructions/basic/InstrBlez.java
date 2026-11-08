package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBlez extends BasicInstruction {
  public InstrBlez() {
    super(
        "blez $t1,label",
        "Branch if less than or equal to zero : Branch to statement at label's address if $t1 is"
            + " less than or equal to zero",
        BasicInstructionFormat.I_BRANCH_FORMAT,
        "000110 fffff 00000 ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[0]) <= 0) {
            InstrHelpers.processBranch(context, operands[1]);
          }
        });
  }
}
