package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSltiu extends BasicInstruction {
    public InstrSltiu() {
        super("sltiu $t1,$t2,-100",
              "Set less than immediate unsigned : If $t2 is less than  sign-extended 16-bit immediate using unsigned comparison, then set $t1 to 1 else set $t1 to 0",
              BasicInstructionFormat.I_FORMAT,
              "001011 sssss fffff tttttttttttttttt",
              statement -> {
              int[] operands = statement.getOperands();
              int first = RegisterFile.getValue(operands[1]);
              
              int second = operands[2] << 16 >> 16;
              if (first >= 0 && second >= 0 || first < 0 && second < 0) {
                RegisterFile.updateRegister(operands[0], (first < second) ? 1 : 0);
              } else {
                RegisterFile.updateRegister(operands[0], (first >= 0) ? 1 : 0);
              }
            });
    }
}
