package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to read from file descriptor given in $a0.  $a1 specifies buffer
 * and $a2 specifies length.  Number of characters read is returned in $v0.
 * (this was changed from $a0 in MARS 3.7 for SPIM compatibility.  The table
 * in COD erroneously shows $a0). *
 */

public class SyscallRead extends AbstractSyscall {
    /**
     * Build an instance of the Read file syscall.  Default service number
     * is 14 and name is "Read".
     */
    public SyscallRead() {
        super(14, "Read");
    }

    /**
     * Performs syscall function to read from file descriptor given in $a0.  $a1 specifies buffer
     * and $a2 specifies length.  Number of characters read is returned in $v0 (starting MARS 3.7).
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        int byteAddress = RegisterFile.getValue(5); // destination of characters read from file
        byte b = 0;
        int index = 0;
        byte[] myBuffer = new byte[RegisterFile.getValue(6)]; // specified length
        // Call to SystemIO.xxxx.read(xxx,xxx,xxx)  returns actual length
        int retLength = SystemIO.readFromFile(
                RegisterFile.getValue(4), // fd
                myBuffer, // buffer
                RegisterFile.getValue(6)); // length
        RegisterFile.updateRegister(2, retLength); // set returned value in register

        // Getting rid of processing exception.  It is the responsibility of the
        // user program to check the syscall's return value.  MARS should not
        // re-emptively terminate MIPS execution because of it.  Thanks to
        // UCLA student Duy Truong for pointing this out.  DPS 28-July-2009
        // copy bytes from returned buffer into MARS memory
        try {
            while (index < retLength) {
                Globals.memory.setByte(byteAddress++,
                        myBuffer[index++]);
            }
        } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }
    }
}