package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrRoundWD extends BasicInstruction {
    public InstrRoundWD() {
        super("round.w.d $f1,$f2",
              "Round double precision to word : Set $f1 to 32-bit integer round of double-precision float in $f2",
              BasicInstructionFormat.R_FORMAT,
              "010001 10001 00000 sssss fffff 001100",
              statement -> { 
              
              
              
              
              int[] operands = statement.getOperands();
              if (operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "second register must be even-numbered");
              }
              double doubleValue =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[1] + 1),
                          Coprocessor1.getValue(operands[1])));
              int below, above;
              int round = (int) Math.round(doubleValue);
              
              if (Double.isNaN(doubleValue)
                  || Double.isInfinite(doubleValue)
                  || doubleValue < (double) Integer.MIN_VALUE
                  || doubleValue > (double) Integer.MAX_VALUE) {
                round = Integer.MAX_VALUE;
              } else {
                double doubleObj = doubleValue;
                
                
                
                if (doubleValue < 0.0) {
                  above = (int) doubleObj; 
                  below = above - 1;
                } else {
                  below = (int) doubleObj; 
                  above = below + 1;
                }
                if (doubleValue - below == above - doubleValue) { 
                  round = (above % 2 == 0) ? above : below;
                }
              }
              Coprocessor1.updateRegister(operands[0], round);
            });
    }
}
