package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMovS extends BasicInstruction {
    public InstrMovS() {
        super("mov.s $f0,$f1",
              "Move floating point single precision : Set single precision $f0 to single precision value in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 00000 sssss fffff 000110",
              statement -> {
              int[] operands = statement.getOperands();
              Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            });
    }
}
