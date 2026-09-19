package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrCEqD2 extends BasicInstruction {
    public InstrCEqD2() {
        super("c.eq.d 1,$f2,$f4",
              "Compare equal double precision : If $f2 is equal to $f4 (double-precision), set Coprocessor 1 condition flag specified by immediate to true else set it to false",
              BasicInstructionFormat.R_FORMAT,
              "010001 10001 ttttt sssss fff 00 110010",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                throw new ProcessingException(statement, "both registers must be even-numbered");
              }
              double op1 =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[1] + 1),
                          Coprocessor1.getValue(operands[1])));
              double op2 =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[2] + 1),
                          Coprocessor1.getValue(operands[2])));
              if (op1 == op2) Coprocessor1.setConditionFlag(operands[0]);
              else Coprocessor1.clearConditionFlag(operands[0]);
            });
    }
}
