package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrCvtSD extends BasicInstruction {
  public InstrCvtSD() {
    super(
        "cvt.s.d $f1,$f2",
        "Convert from double precision to single precision : Set $f1 to single precision equivalent"
            + " of double precision value in $f2",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 00000 sssss fffff 100000",
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
          context.updateCoprocessor1(operands[0], Float.floatToIntBits((float) val));
        });
  }
}
