package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBltz extends BasicInstruction {
    public InstrBltz() {
        super("bltz $t1,label",
              "Branch if less than zero : Branch to statement at label's address if $t1 is less than zero",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "000001 fffff 00000 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) < 0) {
                InstrHelpers.processBranch(operands[1]);
              }
            });
    }
}
