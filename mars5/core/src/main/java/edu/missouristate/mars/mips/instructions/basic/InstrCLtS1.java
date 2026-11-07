package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrCLtS1 extends BasicInstruction {
  public InstrCLtS1() {
    super(
        "c.lt.s $f0,$f1",
        "Compare less than single precision : If $f0 is less than $f1, set Coprocessor 1 condition"
            + " flag 0 true else set it false",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 sssss fffff 00000 111100",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float op1 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[0]));
          float op2 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          if (op1 < op2) context.setConditionFlag(0);
          else context.clearConditionFlag(0);
        });
  }
}
