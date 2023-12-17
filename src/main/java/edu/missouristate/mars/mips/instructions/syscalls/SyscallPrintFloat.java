package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.*;

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