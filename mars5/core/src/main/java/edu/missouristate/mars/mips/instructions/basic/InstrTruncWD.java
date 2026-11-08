package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrTruncWD extends BasicInstruction {
  public InstrTruncWD() {
    super(
        "trunc.w.d $f1,$f2",
        "Truncate double precision to word : Set $f1 to 32-bit integer truncation of"
            + " double-precision float in $f2",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 00000 sssss fffff 001101",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (operands[1] % 2 == 1) {
            throw new ProcessingException(
                context, statement, "second register must be even-numbered");
          }
          double doubleValue =
              Double.longBitsToDouble(
                  Binary.twoIntsToLong(
                      context.coprocessor1.getValue(operands[1] + 1),
                      context.coprocessor1.getValue(operands[1])));

          int truncate = (int) doubleValue;
          if (Double.isNaN(doubleValue)
              || Double.isInfinite(doubleValue)
              || doubleValue < (double) Integer.MIN_VALUE
              || doubleValue > (double) Integer.MAX_VALUE) {
            truncate = Integer.MAX_VALUE;
          }
          context.updateCoprocessor1(operands[0], truncate);
        });
  }
}
