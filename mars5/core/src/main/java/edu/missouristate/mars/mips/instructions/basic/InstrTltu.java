package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTltu extends BasicInstruction {
  public InstrTltu() {
    super(
        "tltu $t1,$t2",
        "Trap if less than unsigned : Trap if $t1 less than $t2, unsigned comparison",
        BasicInstructionFormat.R_FORMAT,
        "000000 fffff sssss ccccc ccccc 110011",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          int first = context.registerFile.getValue(operands[0]);
          int second = context.registerFile.getValue(operands[1]);

          if ((first >= 0 && second >= 0 || first < 0 && second < 0)
              ? (first < second)
              : (first >= 0)) {
            throw new ProcessingException(context, statement, "trap", Exceptions.TRAP_EXCEPTION);
          }
        });
  }
}
