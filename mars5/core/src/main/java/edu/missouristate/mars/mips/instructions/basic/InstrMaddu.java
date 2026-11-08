package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrMaddu extends BasicInstruction {
  public InstrMaddu() {
    super(
        "maddu $t1,$t2",
        "Multiply add unsigned : Multiply $t1 by $t2 then increment HI by high-order 32 bits of"
            + " product, increment LO by low-order 32 bits of product, unsigned (use mfhi to access"
            + " HI, mflo to access LO)",
        BasicInstructionFormat.R_FORMAT,
        "011100 fffff sssss 00000 00000 000001",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          long product =
              (((long) context.registerFile.getValue(operands[0])) << 32 >>> 32)
                  * (((long) context.registerFile.getValue(operands[1])) << 32 >>> 32);

          long contentsHiLo =
              Binary.twoIntsToLong(
                  context.registerFile.getValue(33), context.registerFile.getValue(34));
          long sum = contentsHiLo + product;
          context.updateRegister(33, Binary.highOrderLongToInt(sum));
          context.updateRegister(34, Binary.lowOrderLongToInt(sum));
        });
  }
}
