package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrAddD extends BasicInstruction {
    public InstrAddD() {
        super("add.d $f2,$f4,$f6",
              "Floating point addition double precision : Set $f2 to double-precision floating point value of $f4 plus $f6",
              BasicInstructionFormat.R_FORMAT,
              "010001 10001 ttttt sssss fffff 000000",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[0] % 2 == 1 || operands[1] % 2 == 1 || operands[2] % 2 == 1) {
                throw new ProcessingException(statement, "all registers must be even-numbered");
              }
              double add1 =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[1] + 1),
                          Coprocessor1.getValue(operands[1])));
              double add2 =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[2] + 1),
                          Coprocessor1.getValue(operands[2])));
              double sum = add1 + add2;
              long longSum = Double.doubleToLongBits(sum);
              Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(longSum));
              Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(longSum));
            });
    }
}
