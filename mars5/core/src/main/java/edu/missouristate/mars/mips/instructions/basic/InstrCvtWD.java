package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrCvtWD extends BasicInstruction {
  public InstrCvtWD() {
    super(
        "cvt.w.d $f1,$f2",
        "Convert from double precision to word : Set $f1 to 32-bit integer equivalent of double"
            + " precision value in $f2",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 00000 sssss fffff 100100",
        (statement, context) -> {
          int[] operands = statement.getOperands();

          if (operands[1] % 2 == 1) {
            throw new ProcessingException(
                context, statement, "second register must be even-numbered");
          }
          double val =
              Double.longBitsToDouble(
                  Binary.twoIntsToLong(
                      context.coprocessor1.getValue(operands[1] + 1),
                      context.coprocessor1.getValue(operands[1])));
          context.updateCoprocessor1(operands[0], (int) val);
        });
  }
}
