package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSlti extends BasicInstruction {
  public InstrSlti() {
    super(
        "slti $t1,$t2,-100",
        "Set less than immediate : If $t2 is less than sign-extended 16-bit immediate, then set $t1"
            + " to 1 else set $t1 to 0",
        BasicInstructionFormat.I_FORMAT,
        "001010 sssss fffff tttttttttttttttt",
        (statement, context) -> {
          int[] operands = statement.getOperands();

          context.updateRegister(
              operands[0],
              (context.registerFile.getValue(operands[1]) < (operands[2] << 16 >> 16)) ? 1 : 0);
        });
  }
}
