package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrMovfS1 extends BasicInstruction {
    public InstrMovfS1() {
        super("movf.s $f0,$f1",
              "Move floating point single precision : If condition flag 0 is false, set single precision $f0 to single precision value in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 000 00 sssss fffff 010001",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(0) == 0)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            });
    }
}
