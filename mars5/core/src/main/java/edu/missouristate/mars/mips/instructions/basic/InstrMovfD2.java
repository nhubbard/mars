package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMovfD2 extends BasicInstruction {
  public InstrMovfD2() {
    super(
        "movf.d $f2,$f4,1",
        "Move floating point double precision : If condition flag specified by immediate is false,"
            + " set double precision $f2 to double precision value in $f4",
        BasicInstructionFormat.R_FORMAT,
        "010001 10001 ttt 00 sssss fffff 010001",
        (statement, context) -> {
          int[] operands = statement.getOperands();
          if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
            throw new ProcessingException(
                context, statement, "both registers must be even-numbered");
          }
          if (context.coprocessor1.getConditionFlag(operands[2]) == 0) {
            context.updateCoprocessor1(operands[0], context.coprocessor1.getValue(operands[1]));
            context.updateCoprocessor1(
                operands[0] + 1, context.coprocessor1.getValue(operands[1] + 1));
          }
        });
  }
}
