package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMovn extends BasicInstruction {
  public InstrMovn() {
    super(
        "movn $t1,$t2,$t3",
        "Move conditional not zero : Set $t1 to $t2 if $t3 is not zero",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss ttttt fffff 00000 001011",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[2]) != 0) {
            context.updateRegister(operands[0], context.registerFile.getValue(operands[1]));
          }
        });
  }
}
