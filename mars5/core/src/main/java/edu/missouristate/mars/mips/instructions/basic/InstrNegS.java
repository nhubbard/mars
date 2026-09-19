package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrNegS extends BasicInstruction {
    public InstrNegS() {
        super("neg.s $f0,$f1",
              "Floating point negate single precision : Set single precision $f0 to negation of single precision value in $f1",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 00000 sssss fffff 000111",
              statement -> {
              int[] operands = statement.getOperands();
              int value = Coprocessor1.getValue(operands[1]);
              
              Coprocessor1.updateRegister(
                  operands[0],
                  ((value < 0) ? (value & Integer.MAX_VALUE) : (value | Integer.MIN_VALUE)));
            });
    }
}
