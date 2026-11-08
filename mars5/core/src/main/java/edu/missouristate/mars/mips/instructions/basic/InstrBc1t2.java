package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBc1t2 extends BasicInstruction {
  public InstrBc1t2() {
    super(
        "bc1t 1,label",
        "Branch if specified FP condition flag true (BC1T, not BCLT) : If Coprocessor 1 condition"
            + " flag specified by immediate is true (one) then branch to statement at label's"
            + " address",
        BasicInstructionFormat.I_BRANCH_FORMAT,
        "010001 01000 fff 01 ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.coprocessor1.getConditionFlag(operands[0]) == 1) {
            InstrHelpers.processBranch(context, operands[1]);
          }
        });
  }
}
