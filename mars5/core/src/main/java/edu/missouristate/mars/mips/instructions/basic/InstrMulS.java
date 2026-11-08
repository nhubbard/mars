package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMulS extends BasicInstruction {
  public InstrMulS() {
    super(
        "mul.s $f0,$f1,$f3",
        "Floating point multiplication single precision : Set $f0 to single-precision floating"
            + " point value of $f1 times $f3",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 ttttt sssss fffff 000010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float mul1 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          float mul2 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[2]));
          float prod = mul1 * mul2;
          context.updateCoprocessor1(operands[0], Float.floatToIntBits(prod));
        });
  }
}
