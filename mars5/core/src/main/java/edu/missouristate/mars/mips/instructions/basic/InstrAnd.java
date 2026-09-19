package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrAnd extends BasicInstruction {
    public InstrAnd() {
        super("and $t1,$t2,$t3",
              "Bitwise AND : Set $t1 to bitwise AND of $t2 and $t3",
              BasicInstructionFormat.R_FORMAT,
              "000000 sssss ttttt fffff 00000 100100",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(
                  operands[0],
                  RegisterFile.getValue(operands[1]) & RegisterFile.getValue(operands[2]));
            });
    }
}
