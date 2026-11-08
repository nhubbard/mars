package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrMulD extends BasicInstruction {
  public InstrMulD() {
    super(
        "mul.d $f2,$f4,$f6",
        "Floating point multiplication double precision : Set $f2 to double-precision floating"
            + " point value of $f4 times $f6",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 ttttt sssss fffff 000010",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
            throw new ProcessingException(
                context, statement, "all registers must be even-numbered");
          }
          double mul1 =
              Double.longBitsToDouble(
                  Binary.twoIntsToLong(
                      context.coprocessor1.getValue(operands[1] + 1),
                      context.coprocessor1.getValue(operands[1])));
          double mul2 =
              Double.longBitsToDouble(
                  Binary.twoIntsToLong(
                      context.coprocessor1.getValue(operands[2] + 1),
                      context.coprocessor1.getValue(operands[2])));
          double prod = mul1 * mul2;
          long longProd = Double.doubleToLongBits(prod);
          context.updateCoprocessor1(operands[0] + 1, Binary.highOrderLongToInt(longProd));
          context.updateCoprocessor1(operands[0], Binary.lowOrderLongToInt(longProd));
        });
  }
}
