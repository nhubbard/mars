package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to display character stored in $a0 on the console.
 */

public class SyscallPrintChar extends AbstractSyscall {
    /**
     * Build an instance of the Print Char syscall.  Default service number
     * is 11 and name is "PrintChar".
     */
    public SyscallPrintChar() {
        super(11, "PrintChar");
    }

    /**
     * Performs syscall function to print on the console the character stored in $a0.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // mask off the lower byte of register $a0.
        // Convert to a one-character string and use the string technique.
        char t = (char) (RegisterFile.getValue(4) & 0x000000ff);
        SystemIO.printString(Character.valueOf(t).toString());
    }

}