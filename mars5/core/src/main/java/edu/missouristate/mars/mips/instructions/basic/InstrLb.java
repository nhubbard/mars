package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrLb extends BasicInstruction {
  public InstrLb() {
    super(
        "lb $t1,-100($t2)",
        "Load byte : Set $t1 to sign-extended 8-bit value from effective memory byte address",
        BasicInstructionFormat.I_FORMAT,
        "100000 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          try {
            context.updateRegister(
                operands[0],
                context
                        .memory()
                        .getByte(
                            context.registerFile.getValue(operands[2]) + (operands[1] << 16 >> 16))
                    << 24
                    >> 24);
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
