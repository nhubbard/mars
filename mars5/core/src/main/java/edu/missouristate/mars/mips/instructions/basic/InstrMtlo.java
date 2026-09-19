package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMtlo extends BasicInstruction {
    public InstrMtlo() {
        super("mtlo $t1",
              "Move to LO register : Set LO to contents of $t1 (see multiply and divide operations)",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff 00000 00000 00000 010011",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(34, RegisterFile.getValue(operands[0]));
            });
    }
}
