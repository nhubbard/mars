package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrRoundWS extends BasicInstruction {
    public InstrRoundWS() {
        super("round.w.s $f0,$f1",
              "Round single precision to word : Set $f0 to 32-bit integer round of single-precision float in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 00000 sssss fffff 001100",
              statement -> { 
              
              
              
              
              
              
              int[] operands = statement.getOperands();
              float floatValue = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
              int below, above, round = Math.round(floatValue);
              
              
              
              
              
              
              
              if (Float.isNaN(floatValue)
                  || Float.isInfinite(floatValue)
                  || floatValue < (float) Integer.MIN_VALUE
                  || floatValue > (float) Integer.MAX_VALUE) {
                round = Integer.MAX_VALUE;
              } else {
                float floatObj = floatValue;
                
                
                
                if (floatValue < 0.0F) {
                  above = (int) floatObj; 
                  below = above - 1;
                } else {
                  below = (int) floatObj; 
                  above = below + 1;
                }
                if (floatValue - below == above - floatValue) { 
                  round = (above % 2 == 0) ? above : below;
                }
              }
              Coprocessor1.updateRegister(operands[0], round);
            });
    }
}
