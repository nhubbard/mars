package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMfc0 extends BasicInstruction {
    public InstrMfc0() {
        super("mfc0 $t1,$8",
              "Move from Coprocessor 0 : Set $t1 to the value stored in Coprocessor 0 register $8",
              BasicInstructionFormat.R_FORMAT,
              "010000 00000 fffff sssss 00000 000000",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(operands[0], Coprocessor0.getValue(operands[1]));
            });
    }
}
