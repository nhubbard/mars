package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrNop extends BasicInstruction {
    public InstrNop() {
        super("nop",
              "Null operation : machine code is all zeroes",
              BasicInstructionFormat.R_FORMAT,
              "000000 00000 00000 00000 00000 000000",
              statement -> {
              
            });
    }
}
