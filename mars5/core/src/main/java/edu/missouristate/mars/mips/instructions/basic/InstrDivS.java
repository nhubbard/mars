package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrDivS extends BasicInstruction {
  public InstrDivS() {
    super(
        "div.s $f0,$f1,$f3",
        "Floating point division single precision : Set $f0 to single-precision floating point"
            + " value of $f1 divided by $f3",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 ttttt sssss fffff 000011",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float div1 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          float div2 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[2]));
          float quot = div1 / div2;
          context.updateCoprocessor1(operands[0], Float.floatToIntBits(quot));
        });
  }
}
