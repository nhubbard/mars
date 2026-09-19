package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMfhi extends BasicInstruction {
    public InstrMfhi() {
        super("mfhi $t1",
              "Move from HI register : Set $t1 to contents of HI (see multiply and divide operations)",
              BasicInstructionFormat.R_FORMAT,
              "000000 00000 00000 fffff 00000 010000",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(operands[0], RegisterFile.getValue(33));
            });
    }
}
