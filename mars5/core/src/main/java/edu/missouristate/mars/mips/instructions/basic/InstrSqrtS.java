package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSqrtS extends BasicInstruction {
  public InstrSqrtS() {
    super(
        "sqrt.s $f0,$f1",
        "Square root single precision : Set $f0 to single-precision floating point square root of"
            + " $f1",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 00000 sssss fffff 000100",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float value = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          int floatSqrt;
          if (value < 0.0f) {

            floatSqrt = Float.floatToIntBits(Float.NaN);

          } else {
            floatSqrt = Float.floatToIntBits((float) Math.sqrt(value));
          }
          context.updateCoprocessor1(operands[0], floatSqrt);
        });
  }
}
