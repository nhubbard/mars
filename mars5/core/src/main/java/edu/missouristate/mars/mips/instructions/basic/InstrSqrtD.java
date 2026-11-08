package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrSqrtD extends BasicInstruction {
  public InstrSqrtD() {
    super(
        "sqrt.d $f2,$f4",
        "Square root double precision : Set $f2 to double-precision floating point square root of"
            + " $f4",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 00000 sssss fffff 000100",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
            throw new ProcessingException(
                context, statement, "both registers must be even-numbered");
          }
          double value =
              Double.longBitsToDouble(
                  Binary.twoIntsToLong(
                      context.coprocessor1.getValue(operands[1] + 1),
                      context.coprocessor1.getValue(operands[1])));
          long longSqrt;
          if (value < 0.0) {

            longSqrt = Double.doubleToLongBits(Double.NaN);

          } else {
            longSqrt = Double.doubleToLongBits(Math.sqrt(value));
          }
          context.updateCoprocessor1(operands[0] + 1, Binary.highOrderLongToInt(longSqrt));
          context.updateCoprocessor1(operands[0], Binary.lowOrderLongToInt(longSqrt));
        });
  }
}
