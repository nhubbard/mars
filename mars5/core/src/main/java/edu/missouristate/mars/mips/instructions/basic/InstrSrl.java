package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSrl extends BasicInstruction {
  public InstrSrl() {
    super(
        "srl $t1,$t2,10",
        "Shift right logical : Set $t1 to result of shifting $t2 right by number of bits specified"
            + " by immediate",
        BasicInstructionFormat.R_FORMAT,
        "000000 00000 sssss fffff ttttt 000010",
        (statement, context) -> {
          int[] operands = statement.getOperands();

          context.updateRegister(
              operands[0], context.registerFile.getValue(operands[1]) >>> operands[2]);
        });
  }
}
