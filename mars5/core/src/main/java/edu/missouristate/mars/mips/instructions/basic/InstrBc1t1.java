package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrBc1t1 extends BasicInstruction {
    public InstrBc1t1() {
        super("bc1t label",
              "Branch if FP condition flag 0 true (BC1T, not BCLT) : If Coprocessor 1 condition flag 0 is true (one) then branch to statement at label's address",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "010001 01000 00001 ffffffffffffffff",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(0) == 1) {
                InstrHelpers.processBranch(operands[0]);
              }
            });
    }
}
