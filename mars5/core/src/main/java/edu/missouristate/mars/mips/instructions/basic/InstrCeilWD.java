package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrCeilWD extends BasicInstruction {
    public InstrCeilWD() {
        super("ceil.w.d $f1,$f2",
              "Ceiling double precision to word : Set $f1 to 32-bit integer ceiling of double-precision float in $f2",
              BasicInstructionFormat.R_FORMAT,
              "010001 10001 00000 sssss fffff 001110",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[1] % 2 == 1) {
                throw new ProcessingException(statement, "second register must be even-numbered");
              }
              double doubleValue =
                  Double.longBitsToDouble(
                      Binary.twoIntsToLong(
                          Coprocessor1.getValue(operands[1] + 1),
                          Coprocessor1.getValue(operands[1])));
              
              
              int ceiling = (int) Math.ceil(doubleValue);
              if (Double.isNaN(doubleValue)
                  || Double.isInfinite(doubleValue)
                  || doubleValue < (double) Integer.MIN_VALUE
                  || doubleValue > (double) Integer.MAX_VALUE) {
                ceiling = Integer.MAX_VALUE;
              }
              Coprocessor1.updateRegister(operands[0], ceiling);
            });
    }
}
