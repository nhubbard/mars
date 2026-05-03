package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrBne extends BasicInstruction {
    public InstrBne() {
        super("bne $t1,$t2,label",
              "Branch if not equal : Branch to statement at label's address if $t1 and $t2 are not equal",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "000101 fffff sssss tttttttttttttttt",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) != RegisterFile.getValue(operands[1])) {
                InstrHelpers.processBranch(operands[2]);
              }
            });
    }
}
