package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrCLtD1 extends BasicInstruction {
    public InstrCLtD1() {
        super("c.lt.d $f2,$f4",
              "Compare less than double precision : If $f2 is less than $f4 (double-precision), set Coprocessor 1 condition flag 0 true else set it false",
              BasicInstructionFormat.R_FORMAT,
              "010001 10001 sssss fffff 00000 111100",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[0] % 2 == 1 || operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
              }
              double op1 =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[0] + 1),
                          Coprocessor1.getValue(operands[0])));
              double op2 =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[1] + 1),
                          Coprocessor1.getValue(operands[1])));
              if (op1 < op2) Coprocessor1.setConditionFlag(0);
              else Coprocessor1.clearConditionFlag(0);
            });
    }
}
