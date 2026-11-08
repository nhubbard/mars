package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrTgei extends BasicInstruction {
  public InstrTgei() {
    super(
        "tgei $t1,-100",
        "Trap if greater than or equal to immediate : Trap if $t1 greater than or equal to"
            + " sign-extended 16 bit immediate",
        BasicInstructionFormat.I_FORMAT,
        "000001 fffff 01000 ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[0]) >= (operands[1] << 16 >> 16)) {
            throw new ProcessingException(context, statement, "trap", Exceptions.TRAP_EXCEPTION);
          }
        });
  }
}
