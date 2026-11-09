package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.core.InstructionContext;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.mips.instructions.syscalls.Syscall;
import edu.missouristate.mars.simulator.Exceptions;

class InstrHelpers {

  static void processBranch(InstructionContext context, int displacement) {
    if (context.settings().isDelayedBranchingEnabled()) {
      context.delayedBranch.register(
          context.registerFile.getProgramCounter() + (displacement << 2));
    } else {
      context.setProgramCounter(context.registerFile.getProgramCounter() + (displacement << 2));
    }
  }

  static void processReturnAddress(InstructionContext context, int register) {
    context.link(
        register,
        context.registerFile.getProgramCounter()
            + (context.settings().isDelayedBranchingEnabled()
                ? Instruction.INSTRUCTION_LENGTH
                : 0));
  }

  static void findAndSimulateSyscall(
      InstructionContext context, int number, ProgramStatement statement)
      throws ProcessingException {
    Syscall service = context.syscalls().findByNumber(number);
    if (service != null) {
      service.simulate(statement, context);
      return;
    }
    throw new ProcessingException(
        context,
        statement,
        "invalid or unimplemented syscall service: " + number + " ",
        Exceptions.SYSCALL_EXCEPTION);
  }
}
