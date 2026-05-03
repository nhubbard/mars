package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrBgtz extends BasicInstruction {
    public InstrBgtz() {
        super("bgtz $t1,label",
              "Branch if greater than zero : Branch to statement at label's address if $t1 is greater than zero",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "000111 fffff 00000 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) > 0) {
                InstrHelpers.processBranch(operands[1]);
              }
            });
    }
}
