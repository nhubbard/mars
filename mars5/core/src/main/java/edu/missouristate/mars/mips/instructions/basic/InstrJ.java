package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrJ extends BasicInstruction {
    public InstrJ() {
        super("j target",
              "Jump unconditionally : Jump to statement at target address",
              BasicInstructionFormat.J_FORMAT,
              "000010 ffffffffffffffffffffffffff",
              statement -> {
              int[] operands = statement.getOperands();
              InstrHelpers.processJump(((RegisterFile.getProgramCounter() & 0xF0000000) | (operands[0] << 2)));
            });
    }
}
