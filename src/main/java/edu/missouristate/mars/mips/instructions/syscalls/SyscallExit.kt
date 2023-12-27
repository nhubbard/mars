package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;


/**
 * Service to exit the MIPS program.
 */

public class SyscallExit extends AbstractSyscall {
    /**
     * Build an instance of the Exit syscall.  Default service number
     * is 10 and name is "Exit".
     */
    public SyscallExit() {
        super(10, "Exit");
    }

    /**
     * Performs syscall function to exit the MIPS program.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        throw new ProcessingException();  // empty exception list.
    }
}