package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTgeiu extends BasicInstruction {
  public InstrTgeiu() {
    super(
        "tgeiu $t1,-100",
        "Trap if greater or equal to immediate unsigned : Trap if $t1 greater than or equal to"
            + " sign-extended 16 bit immediate, unsigned comparison",
        BasicInstructionFormat.I_FORMAT,
        "000001 fffff 01001 ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          int first = context.registerFile.getValue(operands[0]);

          int second = operands[1] << 16 >> 16;

          if ((first >= 0 && second >= 0 || first < 0 && second < 0)
              ? (first >= second)
              : (first < 0)) {
            throw new ProcessingException(context, statement, "trap", Exceptions.TRAP_EXCEPTION);
          }
        });
  }
}
