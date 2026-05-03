package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMtc1 extends BasicInstruction {
    public InstrMtc1() {
        super("mtc1 $t1,$f1",
              "Move to Coprocessor 1 (FPU) : Set Coprocessor 1 register $f1 to value in $t1",
              BasicInstructionFormat.R_FORMAT,
              "010001 00100 fffff sssss 00000 000000",
              statement -> {
              int[] operands = statement.getOperands();
              Coprocessor1.updateRegister(operands[1], RegisterFile.getValue(operands[0]));
            });
    }
}
