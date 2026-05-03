package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMovf1 extends BasicInstruction {
    public InstrMovf1() {
        super("movf $t1,$t2",
              "Move if FP condition flag 0 false : Set $t1 to $t2 if FPU (Coprocessor 1) condition flag 0 is false (zero)",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss 000 00 fffff 00000 000001",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(0) == 0)
                RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
            });
    }
}
