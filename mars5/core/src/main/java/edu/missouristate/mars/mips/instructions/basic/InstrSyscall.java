package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;

public class InstrSyscall extends BasicInstruction {
    public InstrSyscall() {
        super("syscall",
              "Issue a system call : Execute the system call specified by value in $v0",
              BasicInstructionFormat.R_FORMAT,
              "000000 ccccc ccccc ccccc ccccc 001100",
              statement -> { InstrHelpers.findAndSimulateSyscall(RegisterFile.getValue(2), statement); });
    }
}
