package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBc1f1 extends BasicInstruction {
    public InstrBc1f1() {
        super("bc1f label",
              "Branch if FP condition flag 0 false (BC1F, not BCLF) : If Coprocessor 1 condition flag 0 is false (zero) then branch to statement at label's address",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "010001 01000 00000 ffffffffffffffff",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(0) == 0) {
                InstrHelpers.processBranch(operands[0]);
              }
            });
    }
}
