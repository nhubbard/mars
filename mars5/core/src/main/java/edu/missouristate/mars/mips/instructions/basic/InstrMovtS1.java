package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMovtS1 extends BasicInstruction {
    public InstrMovtS1() {
        super("movt.s $f0,$f1",
              "Move floating point single precision : If condition flag 0 is true, set single precision $f0 to single precision value in $f1e",
              BasicInstructionFormat.R_FORMAT,
              "010001 10000 000 01 sssss fffff 010001",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(0) == 1)
                Coprocessor1.updateRegister(operands[0], Coprocessor1.getValue(operands[1]));
            });
    }
}
