package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrJal extends BasicInstruction {
  public InstrJal() {
    super(
        "jal target",
        "Jump and link : Set $ra to Program Counter (return address) then jump to statement at"
            + " target address",
        BasicInstructionFormat.J_FORMAT,
        "000011 ffffffffffffffffffffffffff",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          InstrHelpers.processReturnAddress(context, 31);
          context.jump((context.registerFile.getProgramCounter() & 0xF0000000) | (operands[0] << 2));
        });
  }
}
