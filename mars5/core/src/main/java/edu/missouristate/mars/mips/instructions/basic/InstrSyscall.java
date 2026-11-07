package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat;

public class InstrSyscall extends BasicInstruction {
  public InstrSyscall() {
    super(
        "syscall",
        "Issue a system call : Execute the system call specified by value in $v0",
        BasicInstructionFormat.R_FORMAT,
        "000000 ccccc ccccc ccccc ccccc 001100",
        (statement, context) ->
            InstrHelpers.findAndSimulateSyscall(
                context, context.registerFile.getValue(2), statement));
  }
}
