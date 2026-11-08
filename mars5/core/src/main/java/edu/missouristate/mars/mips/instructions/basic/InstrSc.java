package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSc extends BasicInstruction {
  public InstrSc() {
    super(
        "sc $t1,-100($t2)",
        "Store conditional : Paired with Load Linked (ll) to perform atomic read-modify-write. "
            + " Stores $t1 value into effective address, then sets $t1 to 1 for success.  Always"
            + " succeeds because MARS does not simulate multiple processors.",
        BasicInstructionFormat.I_FORMAT,
        "111000 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          try {
            context.setMemoryWord(
                context.registerFile.getValue(operands[2]) + operands[1],
                context.registerFile.getValue(operands[0]));
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
          context.updateRegister(operands[0], 1);
        });
  }
}
