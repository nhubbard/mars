package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSw extends BasicInstruction {
  public InstrSw() {
    super(
        "sw $t1,-100($t2)",
        "Store word : Store contents of $t1 into effective memory word address",
        BasicInstructionFormat.I_FORMAT,
        "101011 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          try {
            context.setMemoryWord(
                context.registerFile.getValue(operands[2]) + operands[1],
                context.registerFile.getValue(operands[0]));
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
