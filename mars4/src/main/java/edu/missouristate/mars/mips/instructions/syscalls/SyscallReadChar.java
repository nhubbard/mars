package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.simulator.Exceptions;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to read a character from input console into $a0.
 */

public class SyscallReadChar extends AbstractSyscall {
    /**
     * Build an instance of the Read Char syscall.  Default service number
     * is 12 and name is "ReadChar".
     */
    public SyscallReadChar() {
        super(12, "ReadChar");
    }

    /**
     * Performs syscall function to read a character from input console into $a0
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        int value;
        try {
            value = SystemIO.readChar(this.getNumber());
        } catch (IndexOutOfBoundsException e) // means null input
        {
            throw new ProcessingException(statement,
                    "invalid char input (syscall " + this.getNumber() + ")",
                    Exceptions.SYSCALL_EXCEPTION);
        }
        // DPS 20 June 2008: changed from 4 ($a0) to 2 ($v0)
        RegisterFile.updateRegister(2, value);
    }

}