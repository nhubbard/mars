package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.hardware.*;


/**
 * Service to exit the MIPS program with return value given in $a0.  Ignored if running from GUI.
 */

public class SyscallExit2 extends AbstractSyscall {
    /**
     * Build an instance of the Exit2 syscall.  Default service number
     * is 17 and name is "Exit2".
     */
    public SyscallExit2() {
        super(17, "Exit2");
    }

    /**
     * Performs syscall function to exit the MIPS program with return value given in $a0.
     * If running in command mode, MARS will exit with that value.  If running under GUI,
     * return value is ignored.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        if (Globals.getGui() == null) {
            Globals.exitCode = RegisterFile.getValue(4);
        }
        throw new ProcessingException(); // empty error list
    }
}