package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to display integer stored in $a0 on the console.
 */

public class SyscallPrintInt extends AbstractSyscall {
    /**
     * Build an instance of the Print Integer syscall.  Default service number
     * is 1 and name is "PrintInt".
     */
    public SyscallPrintInt() {
        super(1, "PrintInt");
    }

    /**
     * Performs syscall function to print on the console the integer stored in $a0.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        SystemIO.printString(
                Integer.valueOf(RegisterFile.getValue(4)).toString());
    }
}