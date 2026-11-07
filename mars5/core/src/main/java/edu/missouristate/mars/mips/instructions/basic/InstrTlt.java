package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTlt extends BasicInstruction {
  public InstrTlt() {
    super(
        "tlt $t1,$t2",
        "Trap if less than: Trap if $t1 less than $t2",
        BasicInstructionFormat.R_FORMAT,
        "000000 fffff sssss ccccc ccccc 110010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[0])
              < context.registerFile.getValue(operands[1])) {
            throw new ProcessingException(context, statement, "trap", Exceptions.TRAP_EXCEPTION);
          }
        });
  }
}
