package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrSub extends BasicInstruction {
  public InstrSub() {
    super(
        "sub $t1,$t2,$t3",
        "Subtraction with overflow : set $t1 to ($t2 minus $t3)",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttttt fffff 00000 100010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          int sub1 = context.registerFile.getValue(operands[1]);
          int sub2 = context.registerFile.getValue(operands[2]);
          int dif = sub1 - sub2;

          if ((sub1 >= 0 && sub2 < 0 && dif < 0) || (sub1 < 0 && sub2 >= 0 && dif >= 0)) {
            throw new ProcessingException(
                context,
                statement,
                "arithmetic overflow",
                Exceptions.ARITHMETIC_OVERFLOW_EXCEPTION);
          }
          context.updateRegister(operands[0], dif);
        });
  }
}
