package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrLwc1 extends BasicInstruction {
  public InstrLwc1() {
    super(
        "lwc1 $f1,-100($t2)",
        "Load word into Coprocessor 1 (FPU) : Set $f1 to 32-bit value from effective memory word"
            + " address",
        BasicInstructionFormat.I_FORMAT,
        "110001 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          try {
            context.updateCoprocessor1(
                operands[0],
                Globals.memory.getWord(context.registerFile.getValue(operands[2]) + operands[1]));
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
