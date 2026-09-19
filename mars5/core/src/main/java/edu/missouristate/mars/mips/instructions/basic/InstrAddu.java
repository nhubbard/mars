package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrAddu extends BasicInstruction {
    public InstrAddu() {
        super("addu $t1,$t2,$t3",
              "Addition unsigned without overflow : set $t1 to ($t2 plus $t3), no overflow",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttttt fffff 00000 100001",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(
                  operands[0],
                  RegisterFile.getValue(operands[1]) + RegisterFile.getValue(operands[2]));
            });
    }
}
