package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrCLeD2 extends BasicInstruction {
  public InstrCLeD2() {
    super(
        "c.le.d 1,$f2,$f4",
        "Compare less or equal double precision : If $f2 is less than or equal to $f4"
            + " (double-precision), set Coprocessor 1 condition flag specfied by immediate true"
            + " else set it false",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 ttttt sssss fff 00 111110",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
            throw new ProcessingException(
                context, statement, "both registers must be even-numbered");
          }
          double op1 =
              Double.longBitsToDouble(
                  Binary.twoIntsToLong(
                      context.coprocessor1.getValue(operands[1] + 1),
                      context.coprocessor1.getValue(operands[1])));
          double op2 =
              Double.longBitsToDouble(
                  Binary.twoIntsToLong(
                      context.coprocessor1.getValue(operands[2] + 1),
                      context.coprocessor1.getValue(operands[2])));
          if (op1 <= op2) {
            context.setConditionFlag(operands[0]);
          } else {
            context.clearConditionFlag(operands[0]);
          }
        });
  }
}
