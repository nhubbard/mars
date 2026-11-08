package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;

public class InstrSdc1 extends BasicInstruction {
  public InstrSdc1() {
    super(
        "sdc1 $f2,-100($t2)",
        "Store double word from Coprocessor 1 (FPU)) : Store 64 bit value in $f2 to effective"
            + " memory doubleword address",
        BasicInstructionFormat.I_FORMAT,
        "111101 ttttt fffff ssssssssssssssss",
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
                    Exceptions.ADDRESS_EXCEPTION_STORE,
                    context.registerFile.getValue(operands[2]) + operands[1]));
          }
          try {
            context.setMemoryWord(
                context.registerFile.getValue(operands[2]) + operands[1],
                context.coprocessor1.getValue(operands[0]));
            context.setMemoryWord(
                context.registerFile.getValue(operands[2]) + operands[1] + 4,
                context.coprocessor1.getValue(operands[0] + 1));
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
