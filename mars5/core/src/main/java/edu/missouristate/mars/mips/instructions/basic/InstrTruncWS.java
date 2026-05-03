package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrTruncWS extends BasicInstruction {
    public InstrTruncWS() {
        super("trunc.w.s $f0,$f1",
              "Truncate single precision to word : Set $f0 to 32-bit integer truncation of single-precision float in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 00000 sssss fffff 001101",
              statement -> {
              int[] operands = statement.getOperands();
              float floatValue = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
              int truncate =
                  (int) floatValue; 
              
              
              if (Float.isNaN(floatValue)
                  || Float.isInfinite(floatValue)
                  || floatValue < (float) Integer.MIN_VALUE
                  || floatValue > (float) Integer.MAX_VALUE) {
                truncate = Integer.MAX_VALUE;
              }
              Coprocessor1.updateRegister(operands[0], truncate);
            });
    }
}
