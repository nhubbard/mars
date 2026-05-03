package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrBgez extends BasicInstruction {
    public InstrBgez() {
        super("bgez $t1,label",
              "Branch if greater than or equal to zero : Branch to statement at label's address if $t1 is greater than or equal to zero",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "000001 fffff 00001 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) >= 0) {
                InstrHelpers.processBranch(operands[1]);
              }
            });
    }
}
