package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrLh extends BasicInstruction {
  public InstrLh() {
    super(
        "lh $t1,-100($t2)",
        "Load halfword : Set $t1 to sign-extended 16-bit value from effective memory halfword"
            + " address",
        BasicInstructionFormat.I_FORMAT,
        "100001 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          try {
            context.updateRegister(
                operands[0],
                Globals.memory.getHalf(
                        context.registerFile.getValue(operands[2]) + (operands[1] << 16 >> 16))
                    << 16
                    >> 16);
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
