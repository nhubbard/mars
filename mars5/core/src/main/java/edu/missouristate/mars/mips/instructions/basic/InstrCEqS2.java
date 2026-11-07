package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrCEqS2 extends BasicInstruction {
  public InstrCEqS2() {
    super(
        "c.eq.s 1,$f0,$f1",
        "Compare equal single precision : If $f0 is equal to $f1, set Coprocessor 1 condition flag"
            + " specied by immediate to true else set it to false",
        BasicInstructionFormat.R_FORMAT,
        "010001 10000 ttttt sssss fff 00 11 0010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          float op1 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[1]));
          float op2 = Float.intBitsToFloat(context.coprocessor1.getValue(operands[2]));
          if (op1 == op2) context.setConditionFlag(operands[0]);
          else context.clearConditionFlag(operands[0]);
        });
  }
}
