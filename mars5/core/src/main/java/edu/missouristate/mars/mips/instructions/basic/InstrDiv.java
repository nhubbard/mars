package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrDiv extends BasicInstruction {
  public InstrDiv() {
    super(
        "div $t1,$t2",
        "Division with overflow : Divide $t1 by $t2 then set LO to quotient and HI to remainder"
            + " (use mfhi to access HI, mflo to access LO)",
        BasicInstructionFormat.R_FORMAT,
        "000000 fffff sssss 00000 00000 011010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (context.registerFile.getValue(operands[1]) == 0) {

            return;
          }

          context.updateRegister(
              33,
              context.registerFile.getValue(operands[0])
                  % context.registerFile.getValue(operands[1]));
          context.updateRegister(
              34,
              context.registerFile.getValue(operands[0])
                  / context.registerFile.getValue(operands[1]));
        });
  }
}
