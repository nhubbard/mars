package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.SystemIO;


/**
 * Service to close file descriptor given in $a0.
 */

public class SyscallClose extends AbstractSyscall {
    /**
     * Build an instance of the Close syscall.  Default service number
     * is 16 and name is "Close".
     */
    public SyscallClose() {
        super(16, "Close");
    }

    /**
     * Performs syscall function to close file descriptor given in $a0.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        SystemIO.closeFile(RegisterFile.getValue(4));
    }
}