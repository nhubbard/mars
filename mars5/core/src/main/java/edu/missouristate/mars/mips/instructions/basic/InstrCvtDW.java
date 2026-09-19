package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.util.*;

public class InstrCvtDW extends BasicInstruction {
    public InstrCvtDW() {
        super("cvt.d.w $f2,$f1",
              "Convert from word to double precision : Set $f2 to double precision equivalent of 32-bit integer value in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10100 00000 sssss fffff 100001",
              statement -> {
              int[] operands = statement.getOperands();
              if (operands[0] % 2 == 1) {
                throw new ProcessingException(statement, "first register must be even-numbered");
              }
              
              long result = Double.doubleToLongBits(Coprocessor1.getValue(operands[1]));
              Coprocessor1.updateRegister(operands[0] + 1, Binary.highOrderLongToInt(result));
              Coprocessor1.updateRegister(operands[0], Binary.lowOrderLongToInt(result));
            });
    }
}
