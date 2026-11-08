package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrCEqS1 extends BasicInstruction {
  public InstrCEqS1() {
    super(
        "c.eq.s $f0,$f1",
        "Compare equal single precision : If $f0 is equal to $f1, set Coprocessor 1 condition flag"
            + " 0 true else set it false",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 sssss fffff 00000 110010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float op1 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[0]));
          float op2 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          if (op1 == op2) {
            context.setConditionFlag(0);
          } else {
            context.clearConditionFlag(0);
          }
        });
  }
}
