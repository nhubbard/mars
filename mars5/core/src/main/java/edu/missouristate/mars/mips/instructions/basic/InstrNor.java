package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrNor extends BasicInstruction {
  public InstrNor() {
    super(
        "nor $t1,$t2,$t3",
        "Bitwise NOR : Set $t1 to bitwise NOR of $t2 and $t3",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttttt fffff 00000 100111",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          context.updateRegister(
              operands[0],
              ~(context.registerFile.getValue(operands[1])
                  | context.registerFile.getValue(operands[2])));
        });
  }
}
