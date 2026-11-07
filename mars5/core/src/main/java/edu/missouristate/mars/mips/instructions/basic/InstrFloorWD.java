package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrFloorWD extends BasicInstruction {
  public InstrFloorWD() {
    super(
        "floor.w.d $f1,$f2",
        "Floor double precision to word : Set $f1 to 32-bit integer floor of double-precision float"
            + " in $f2",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 00000 sssss fffff 001111",
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

          int floor = (int) Math.floor(doubleValue);
          if (Double.isNaN(doubleValue)
              || Double.isInfinite(doubleValue)
              || doubleValue < (double) Integer.MIN_VALUE
              || doubleValue > (double) Integer.MAX_VALUE) {
            floor = Integer.MAX_VALUE;
          }
          context.updateCoprocessor1(operands[0], floor);
        });
  }
}
