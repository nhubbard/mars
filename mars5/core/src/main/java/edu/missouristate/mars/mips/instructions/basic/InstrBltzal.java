package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrBltzal extends BasicInstruction {
    public InstrBltzal() {
        super("bltzal $t1,label",
              "Branch if less than zero and link : If $t1 is less than or equal to zero, then set $ra to the Program Counter and branch to statement at label's address",
              BasicInstructionFormat.I_BRANCH_FORMAT,
              "000001 fffff 10000 ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              if (RegisterFile.getValue(operands[0]) < 0) { 
                InstrHelpers.processReturnAddress(
                    31); 
                InstrHelpers.processBranch(operands[1]);
              }
            });
    }
}
