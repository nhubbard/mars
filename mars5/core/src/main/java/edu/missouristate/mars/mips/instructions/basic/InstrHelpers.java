package edu.missouristate.mars.mips.instructions.basic;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.mips.instructions.syscalls.Syscall;
import edu.missouristate.mars.mips.instructions.SyscallRegistry;
import edu.missouristate.mars.simulator.DelayedBranch;
import edu.missouristate.mars.simulator.Exceptions;

class InstrHelpers {

    static void processBranch(int displacement) {
        if (Globals.getSettings().getDelayedBranchingEnabled()) {
            DelayedBranch.register(RegisterFile.getProgramCounter() + (displacement << 2));
        } else {
            RegisterFile.setProgramCounter(
                RegisterFile.getProgramCounter() + (displacement << 2));
        }
    }

    static void processJump(int targetAddress) {
        if (Globals.getSettings().getDelayedBranchingEnabled()) {
            DelayedBranch.register(targetAddress);
        } else {
            RegisterFile.setProgramCounter(targetAddress);
        }
    }

    static void processReturnAddress(int register) {
        RegisterFile.updateRegister(
            register,
            RegisterFile.getProgramCounter()
                + (Globals.getSettings().getDelayedBranchingEnabled()
                    ? Instruction.INSTRUCTION_LENGTH : 0));
    }

    static void findAndSimulateSyscall(int number, ProgramStatement statement)
            throws ProcessingException {
        Syscall service = SyscallRegistry.findByNumber(number);
        if (service != null) {
            service.simulate(statement);
            return;
        }
        throw new ProcessingException(
            statement,
            "invalid or unimplemented syscall service: " + number + " ",
            Exceptions.SYSCALL_EXCEPTION);
    }
}
