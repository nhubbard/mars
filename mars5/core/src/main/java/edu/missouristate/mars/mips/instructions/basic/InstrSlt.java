package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSlt extends BasicInstruction {
  public InstrSlt() {
    super(
        "slt $t1,$t2,$t3",
        "Set less than : If $t2 is less than $t3, then set $t1 to 1 else set $t1 to 0",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttttt fffff 00000 101010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          context.updateRegister(
              operands[0],
              (context.registerFile.getValue(operands[1])
                      < context.registerFile.getValue(operands[2]))
                  ? 1
                  : 0);
        });
  }
}
