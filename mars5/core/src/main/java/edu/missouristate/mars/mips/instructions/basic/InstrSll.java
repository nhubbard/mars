package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSll extends BasicInstruction {
    public InstrSll() {
        super("sll $t1,$t2,10",
              "Shift left logical : Set $t1 to result of shifting $t2 left by number of bits specified by immediate",
              BasicInstructionFormat.R_FORMAT,
              "000000 00000 sssss fffff ttttt 000000",
              statement -> {
              int[] operands = statement.getOperands();
              RegisterFile.updateRegister(
                  operands[0], RegisterFile.getValue(operands[1]) << operands[2]);
            });
    }
}
