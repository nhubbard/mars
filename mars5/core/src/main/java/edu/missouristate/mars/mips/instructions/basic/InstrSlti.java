package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSlti extends BasicInstruction {
    public InstrSlti() {
        super("slti $t1,$t2,-100",
              "Set less than immediate : If $t2 is less than sign-extended 16-bit immediate, then set $t1 to 1 else set $t1 to 0",
              BasicInstructionFormat.I_FORMAT,
              "001010 sssss fffff tttttttttttttttt",
              statement -> {
              int[] operands = statement.getOperands();
              
              RegisterFile.updateRegister(
                  operands[0],
                  (RegisterFile.getValue(operands[1]) < (operands[2] << 16 >> 16)) ? 1 : 0);
            });
    }
}
