package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTeq extends BasicInstruction {
  public InstrTeq() {
    super(
        "teq $t1,$t2",
        "Trap if equal : Trap if $t1 is equal to $t2",
        BasicInstructionFormat.R_FORMAT,
        "000000 fffff sssss ccccc ccccc 110100",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[0])
              == context.registerFile.getValue(operands[1])) {
            throw new ProcessingException(context, statement, "trap", Exceptions.TRAP_EXCEPTION);
          }
        });
  }
}
