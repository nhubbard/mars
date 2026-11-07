package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrMsubu extends BasicInstruction {
  public InstrMsubu() {
    super(
        "msubu $t1,$t2",
        "Multiply subtract unsigned : Multiply $t1 by $t2 then decrement HI by high-order 32 bits"
            + " of product, decement LO by low-order 32 bits of product, unsigned (use mfhi to"
            + " access HI, mflo to access LO)",
        BasicInstructionFormat.R_FORMAT,
        "011100 fffff sssss 00000 00000 000101",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          long product =
              (((long) context.registerFile.getValue(operands[0])) << 32 >>> 32)
                  * (((long) context.registerFile.getValue(operands[1])) << 32 >>> 32);

          long contentsHiLo =
              Binary.twoIntsToLong(
                  context.registerFile.getValue(33), context.registerFile.getValue(34));
          long diff = contentsHiLo - product;
          context.updateRegister(33, Binary.highOrderLongToInt(diff));
          context.updateRegister(34, Binary.lowOrderLongToInt(diff));
        });
  }
}
