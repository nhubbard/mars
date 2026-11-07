package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrJalr1 extends BasicInstruction {
  public InstrJalr1() {
    super(
        "jalr $t1,$t2",
        "Jump and link register : Set $t1 to Program Counter (return address) then jump to"
            + " statement whose address is in $t2",
        BasicInstructionFormat.R_FORMAT,
        "000000 sssss 00000 fffff 00000 001001",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          InstrHelpers.processReturnAddress(context, operands[0]);

          InstrHelpers.processJump(context, context.registerFile.getValue(operands[1]));
        });
  }
}
