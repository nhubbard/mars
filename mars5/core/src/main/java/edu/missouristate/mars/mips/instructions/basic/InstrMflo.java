package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMflo extends BasicInstruction {
    public InstrMflo() {
        super("mflo $t1",
              "Move from LO register : Set $t1 to contents of LO (see multiply and divide operations)",
              BasicInstructionFormat.R_FORMAT,
              "000000 00000 00000 fffff 00000 010010",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(operands[0], RegisterFile.getValue(34));
            });
    }
}
