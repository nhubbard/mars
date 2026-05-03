package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMthi extends BasicInstruction {
    public InstrMthi() {
        super("mthi $t1",
              "Move to HI registerr : Set HI to contents of $t1 (see multiply and divide operations)",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff 00000 00000 00000 010001",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(33, RegisterFile.getValue(operands[0]));
            });
    }
}
