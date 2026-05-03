package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSqrtS extends BasicInstruction {
    public InstrSqrtS() {
        super("sqrt.s $f0,$f1",
              "Square root single precision : Set $f0 to single-precision floating point square root of $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 00000 sssss fffff 000100",
              statement -> {
              int[] operands = statement.getOperands();
              float value = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
              int floatSqrt;
              if (value < 0.0f) {
                
                
                
                
                
                
                
                
                floatSqrt = Float.floatToIntBits(Float.NaN);
                
                
              } else {
                floatSqrt = Float.floatToIntBits((float) Math.sqrt(value));
              }
              Coprocessor1.updateRegister(operands[0], floatSqrt);
            });
    }
}
