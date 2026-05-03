package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrBeq extends BasicInstruction {
    public InstrBeq() {
        super("beq $t1,$t2,label",
              "Branch if equal : Branch to statement at label's address if $t1 and $t2 are equal",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "000100 fffff sssss tttttttttttttttt",
              statement -> {
              int[] operands = statement.getOperands();

              if (RegisterFile.getValue(operands[0]) == RegisterFile.getValue(operands[1])) {
                InstrHelpers.processBranch(operands[2]);
              }
            });
    }
}
