package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrLdc1 extends BasicInstruction {
  public InstrLdc1() {
    super(
        "ldc1 $f2,-100($t2)",
        "Load double word Coprocessor 1 (FPU)) : Set $f2 to 64-bit value from effective memory"
            + " doubleword address",
        BasicInstructionFormat.I_FORMAT,
        "110101 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (operands[0] % 2 == 1) {
            throw new ProcessingException(
                context, statement, "first register must be even-numbered");
          }

          if (!Memory.doublewordAligned(context.registerFile.getValue(operands[2]) + operands[1])) {
            throw new ProcessingException(
                context,
                statement,
                new AddressErrorException(
                    "address not aligned on doubleword boundary ",
                    Exceptions.ADDRESS_EXCEPTION_LOAD,
                    context.registerFile.getValue(operands[2]) + operands[1]));
          }

          try {
            context.updateCoprocessor1(
                operands[0],
                Globals.memory.getWord(context.registerFile.getValue(operands[2]) + operands[1]));
            context.updateCoprocessor1(
                operands[0] + 1,
                Globals.memory.getWord(
                    context.registerFile.getValue(operands[2]) + operands[1] + 4));
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
