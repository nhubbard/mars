package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSlt extends BasicInstruction {
    public InstrSlt() {
        super("slt $t1,$t2,$t3",
              "Set less than : If $t2 is less than $t3, then set $t1 to 1 else set $t1 to 0",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttttt fffff 00000 101010",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(
                  operands[0],
                  (RegisterFile.getValue(operands[1]) < RegisterFile.getValue(operands[2]))
                      ? 1
                      : 0);
            });
    }
}
