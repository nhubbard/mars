package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to display string stored starting at address in $a0 onto the console.
 */

public class SyscallPrintString extends AbstractSyscall {
    /**
     * Build an instance of the Print String syscall.  Default service number
     * is 4 and name is "PrintString".
     */
    public SyscallPrintString() {
        super(4, "PrintString");
    }

    /**
     * Performs syscall function to print string stored starting at address in $a0.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        int byteAddress = RegisterFile.getValue(4);
        char ch = 0;
        try {
            ch = (char) Globals.memory.getByte(byteAddress);
            // won't stop until NULL byte reached!
            while (ch != 0) {
                SystemIO.printString(Character.valueOf(ch).toString());
                byteAddress++;
                ch = (char) Globals.memory.getByte(byteAddress);
            }
        } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }
    }
}