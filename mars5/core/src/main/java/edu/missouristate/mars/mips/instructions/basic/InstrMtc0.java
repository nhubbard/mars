package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMtc0 extends BasicInstruction {
    public InstrMtc0() {
        super("mtc0 $t1,$8",
              "Move to Coprocessor 0 : Set Coprocessor 0 register $8 to value stored in $t1",
              BasicInstructionFormat.R_FORMAT,
              "010000 00100 fffff sssss 00000 000000",
              statement -> {
              int[] operands = statement.getOperands();
              Coprocessor0.updateRegister(operands[1], RegisterFile.getValue(operands[0]));
            });
    }
}
