package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMovfS2 extends BasicInstruction {
    public InstrMovfS2() {
        super("movf.s $f0,$f1,1",
              "Move floating point single precision : If condition flag specified by immediate is false, set single precision $f0 to single precision value in $f1e",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 ttt 00 sssss fffff 010001",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(operands[2]) == 0)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            });
    }
}
