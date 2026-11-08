package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrClo extends BasicInstruction {
  public InstrClo() {
    super(
        "clo $t1,$t2",
        "Count number of leading ones : Set $t1 to the count of leading one bits in $t2 starting at"
            + " most significant bit position",
        BasicInstructionFormat.R_FORMAT,
        "011100 sssss 00000 fffff 00000 100001",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          int value = context.registerFile.getValue(operands[1]);
          int leadingOnes = 0;
          int bitPosition = 31;
          while (Binary.bitValue(value, bitPosition) == 1 && bitPosition >= 0) {
            leadingOnes++;
            bitPosition--;
          }
          context.updateRegister(operands[0], leadingOnes);
        });
  }
}
