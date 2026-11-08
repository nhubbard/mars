package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrCLeS1 extends BasicInstruction {
  public InstrCLeS1() {
    super(
        "c.le.s $f0,$f1",
        "Compare less or equal single precision : If $f0 is less than or equal to $f1, set"
            + " Coprocessor 1 condition flag 0 true else set it false",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 sssss fffff 00000 111110",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float op1 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[0]));
          float op2 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          if (op1 <= op2) {
            context.setConditionFlag(0);
          } else {
            context.clearConditionFlag(0);
          }
        });
  }
}
