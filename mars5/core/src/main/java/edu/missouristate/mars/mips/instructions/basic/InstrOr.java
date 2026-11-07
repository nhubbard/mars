package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrOr extends BasicInstruction {
  public InstrOr() {
    super(
        "or $t1,$t2,$t3",
        "Bitwise OR : Set $t1 to bitwise OR of $t2 and $t3",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttttt fffff 00000 100101",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          context.updateRegister(
              operands[0],
              context.registerFile.getValue(operands[1])
                  | context.registerFile.getValue(operands[2]));
        });
  }
}
