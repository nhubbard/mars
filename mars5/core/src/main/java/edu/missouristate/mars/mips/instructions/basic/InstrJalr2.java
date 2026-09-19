package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrJalr2 extends BasicInstruction {
    public InstrJalr2() {
        super("jalr $t1",
              "Jump and link register : Set $ra to Program Counter (return address) then jump to statement whose address is in $t1",
              BasicInstructionFormat.R_FORMAT,
              "000000 fffff 00000 11111 00000 001001",
              statement -> {
              int[] operands = statement.getOperands();
              InstrHelpers.processReturnAddress(
                  31); 
              InstrHelpers.processJump(RegisterFile.getValue(operands[0]));
            });
    }
}
