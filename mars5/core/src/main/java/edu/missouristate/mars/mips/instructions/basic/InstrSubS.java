package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSubS extends BasicInstruction {
    public InstrSubS() {
        super("sub.s $f0,$f1,$f3",
              "Floating point subtraction single precision : Set $f0 to single-precision floating point value of $f1  minus $f3",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 ttttt sssss fffff 000001",
              statement -> {
              int[] operands = statement.getOperands();
              float sub1 = Float.intBitsToFloat(Coprocessor1.getValue(operands[1]));
              float sub2 = Float.intBitsToFloat(Coprocessor1.getValue(operands[2]));
              float diff = sub1 - sub2;
              Coprocessor1.updateRegister(operands[0], Float.floatToIntBits(diff));
            });
    }
}
