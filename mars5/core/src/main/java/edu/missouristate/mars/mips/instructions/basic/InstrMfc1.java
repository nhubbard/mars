package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMfc1 extends BasicInstruction {
    public InstrMfc1() {
        super("mfc1 $t1,$f1",
              "Move from Coprocessor 1 (FPU) : Set $t1 to value in Coprocessor 1 register $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 00000 fffff sssss 00000 000000",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            });
    }
}
