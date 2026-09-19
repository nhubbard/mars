package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrMovf2 extends BasicInstruction {
    public InstrMovf2() {
        super("movf $t1,$t2,1",
              "Move if specified FP condition flag false : Set $t1 to $t2 if FPU (Coprocessor 1) condition flag specified by the immediate is false (zero)",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttt 00 fffff 00000 000001",
              statement -> {
              int[] operands = statement.getOperands();
              if (Coprocessor1.getConditionFlag(operands[2]) == 0)
                RegisterFile.updateRegister(operands[0], RegisterFile.getValue(operands[1]));
            });
    }
}
