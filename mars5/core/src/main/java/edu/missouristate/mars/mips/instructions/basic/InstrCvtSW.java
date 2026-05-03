package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrCvtSW extends BasicInstruction {
    public InstrCvtSW() {
        super("cvt.s.w $f0,$f1",
              "Convert from word to single precision : Set $f0 to single precision equivalent of 32-bit integer value in $f2",
              BasicInstructionFormat.R_FORMAT,
              "010001 10100 00000 sssss fffff 100000",
              statement -> {
              int[] operands = statement.getOperands();
              
              Coprocessor1.updateRegister(
                  operands[0], Float.floatToIntBits((float) Coprocessor1.getValue(operands[1])));
            });
    }
}
