package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrAddS extends BasicInstruction {
  public InstrAddS() {
    super(
        "add.s $f0,$f1,$f3",
        "Floating point addition single precision : Set $f0 to single-precision floating point"
            + " value of $f1 plus $f3",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 ttttt sssss fffff 000000",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float add1 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          float add2 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[2]));
          float sum = add1 + add2;

          context.updateCoprocessor1(operands[0], Float.floatToIntBits(sum));
        });
  }
}
