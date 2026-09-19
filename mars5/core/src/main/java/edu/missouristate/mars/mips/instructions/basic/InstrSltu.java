package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSltu extends BasicInstruction {
    public InstrSltu() {
        super("sltu $t1,$t2,$t3",
              "Set less than unsigned : If $t2 is less than $t3 using unsigned comparision, then set $t1 to 1 else set $t1 to 0",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttttt fffff 00000 101011",
              statement -> {
              int[] operands = statement.getOperands();
              int first = RegisterFile.getValue(operands[1]);
              int second = RegisterFile.getValue(operands[2]);
              if (first >= 0 && second >= 0 || first < 0 && second < 0) {
                RegisterFile.updateRegister(operands[0], (first < second) ? 1 : 0);
              } else {
                RegisterFile.updateRegister(operands[0], (first >= 0) ? 1 : 0);
              }
            });
    }
}
