package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrJal extends BasicInstruction {
    public InstrJal() {
        super("jal target",
              "Jump and link : Set $ra to Program Counter (return address) then jump to statement at target address",
              BasicInstructionFormat.J_FORMAT,
              "000011 ffffffffffffffffffffffffff",
              statement -> {
              int[] operands = statement.getOperands();
              InstrHelpers.processReturnAddress(
                  31); 
              InstrHelpers.processJump((RegisterFile.getProgramCounter() & 0xF0000000) | (operands[0] << 2));
            });
    }
}
