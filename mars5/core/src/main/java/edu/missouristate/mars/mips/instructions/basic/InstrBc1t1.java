package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBc1t1 extends BasicInstruction {
  public InstrBc1t1() {
    super(
        "bc1t label",
        "Branch if FP condition flag 0 true (BC1T, not BCLT) : If Coprocessor 1 condition flag 0 is"
            + " true (one) then branch to statement at label's address",
        BasicInstructionFormat.I_BRANCH_FORMAT,
        "010001 01000 00001 ffffffffffffffff",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.coprocessor1.getConditionFlag(0) == 1) {
            InstrHelpers.processBranch(context, operands[0]);
          }
        });
  }
}
