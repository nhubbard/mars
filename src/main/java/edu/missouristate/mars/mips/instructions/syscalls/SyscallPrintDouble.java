package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.*;

/**
 * Service to display double whose bits are stored in $f12 & $f13 onto the console.
 * $f13 contains high order word of the double.
 */

public class SyscallPrintDouble extends AbstractSyscall {
    /**
     * Build an instance of the Print Double syscall.  Default service number
     * is 3 and name is "PrintDouble".
     */
    public SyscallPrintDouble() {
        super(3, "PrintDouble");
    }

    /**
     * Performs syscall function to print double whose bits are stored in $f12 & $f13.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Note: Higher numbered reg contains high order word so concat 13-12.
        SystemIO.printString(Double.valueOf(Double.longBitsToDouble(
                Binary.twoIntsToLong(Coprocessor1.getValue(13), Coprocessor1.getValue(12))
        )).toString());
    }
}