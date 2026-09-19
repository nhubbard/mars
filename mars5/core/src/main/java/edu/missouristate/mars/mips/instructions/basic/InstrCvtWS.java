package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrCvtWS extends BasicInstruction {
    public InstrCvtWS() {
        super("cvt.w.s $f0,$f1",
              "Convert from single precision to word : Set $f0 to 32-bit integer equivalent of single precision value in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 00000 sssss fffff 100100",
              statement -> {
              int[] operands = statement.getOperands();
              
              Coprocessor1.updateRegister(
                  operands[0], (int) Float.intBitsToFloat(Coprocessor1.getValue(operands[1])));
            });
    }
}
