package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrLl extends BasicInstruction {
  public InstrLl() {
    super(
        "ll $t1,-100($t2)",
        "Load linked : Paired with Store Conditional (sc) to perform atomic read-modify-write. "
            + " Treated as equivalent to Load Word (lw) because MARS does not simulate multiple"
            + " processors.",
        BasicInstructionFormat.I_FORMAT,
        "110000 ttttt fffff ssssssssssssssss",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          try {
            context.updateRegister(
                operands[0],
                context.memory().getWord(context.registerFile.getValue(operands[2]) + operands[1]));
          } catch (AddressErrorException e) {
            throw new ProcessingException(context, statement, e);
          }
        });
  }
}
