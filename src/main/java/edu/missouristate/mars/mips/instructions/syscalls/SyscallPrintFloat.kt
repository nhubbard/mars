package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to display on the console float whose bits are stored in $f12
 */

public class SyscallPrintFloat extends AbstractSyscall {
    /**
     * Build an instance of the Print Float syscall.  Default service number
     * is 2 and name is "PrintFloat".
     */
    public SyscallPrintFloat() {
        super(2, "PrintFloat");
    }

    /**
     * Performs syscall function to display float whose bits are stored in $f12
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        SystemIO.printString(Float.valueOf(Float.intBitsToFloat(
                Coprocessor1.getValue(12))).toString());
    }
}