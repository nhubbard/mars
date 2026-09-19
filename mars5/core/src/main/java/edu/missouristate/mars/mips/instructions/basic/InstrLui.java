package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrLui extends BasicInstruction {
    public InstrLui() {
        super("lui $t1,100",
              "Load upper immediate : Set high-order 16 bits of $t1 to 16-bit immediate and low-order 16 bits to 0",
              BasicInstructionFormat.I_FORMAT,
              "001111 00000 fffff ssssssssssssssss",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(operands[0], operands[1] << 16);
            });
    }
}
