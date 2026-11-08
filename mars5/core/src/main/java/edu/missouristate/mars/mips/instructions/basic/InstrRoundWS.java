package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrRoundWS extends BasicInstruction {
  public InstrRoundWS() {
    super(
        "round.w.s $f0,$f1",
        "Round single precision to word : Set $f0 to 32-bit integer round of single-precision float"
            + " in $f1",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 00000 sssss fffff 001100",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float floatValue = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          int below, above, round = Math.round(floatValue);

          if (Float.isNaN(floatValue)
              || Float.isInfinite(floatValue)
              || floatValue < (float) Integer.MIN_VALUE
              || floatValue > (float) Integer.MAX_VALUE) {
            round = Integer.MAX_VALUE;
          } else {

            if (floatValue < 0.0F) {
              above = (int) floatValue;
              below = above - 1;
            } else {
              below = (int) floatValue;
              above = below + 1;
            }
            if (floatValue - below == above - floatValue) {
              round = (above % 2 == 0) ? above : below;
            }
          }
          context.updateCoprocessor1(operands[0], round);
        });
  }
}
