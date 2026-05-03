package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrBc1f2 extends BasicInstruction {
    public InstrBc1f2() {
        super("bc1f 1,label",
              "Branch if specified FP condition flag false (BC1F, not BCLF) : If Coprocessor 1 condition flag specified by immediate is false (zero) then branch to statement at label's address",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "010001 01000 fff 00 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(operands[0]) == 0) {
                InstrHelpers.processBranch(operands[1]);
              }
            });
    }
}
