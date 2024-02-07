package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.Binary;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to display integer stored in $a0 on the console as unsigned decimal.
 */

public class SyscallPrintIntUnsigned extends AbstractSyscall {
    /**
     * Build an instance of the Print Integer Unsigned syscall.  Default service number
     * is 36 and name is "PrintIntUnsigned".
     */
    public SyscallPrintIntUnsigned() {
        super(36, "PrintIntUnsigned");
    }

    /**
     * Performs syscall function to print on the console the integer stored in $a0.
     * The value is treated as unsigned.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        SystemIO.printString(
                Binary.unsignedIntToIntString(RegisterFile.getValue(4)));
    }
}