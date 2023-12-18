package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to write to file descriptor given in $a0.  $a1 specifies buffer
 * and $a2 specifies length.  Number of characters written is returned in $v0
 * (this was changed from $a0 in MARS 3.7 for SPIM compatibility.  The table
 * in COD erroneously shows $a0).
 */

public class SyscallWrite extends AbstractSyscall {
    /**
     * Build an instance of the Write file syscall.  Default service number
     * is 15 and name is "Write".
     */
    public SyscallWrite() {
        super(15, "Write");
    }

    /**
     * Performs syscall function to write to file descriptor given in $a0.  $a1 specifies buffer
     * and $a2 specifies length.  Number of characters written is returned in $v0, starting in MARS 3.7.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        int byteAddress = RegisterFile.getValue(5); // source of characters to write to file
        byte b;
        int reqLength = RegisterFile.getValue(6); // user-requested length
        int index = 0;
        byte[] myBuffer = new byte[RegisterFile.getValue(6) + 1]; // specified length plus null termination
        try {
            b = (byte) Globals.memory.getByte(byteAddress);
            while (index < reqLength) // Stop at requested length. Null bytes are included.
            // while (index < reqLength && b != 0) // Stop at requested length OR null byte
            {
                myBuffer[index++] = b;
                byteAddress++;
                b = (byte) Globals.memory.getByte(byteAddress);
            }

            myBuffer[index] = 0; // Add string termination
        } // end try
        catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }
        int retValue = SystemIO.writeToFile(
                RegisterFile.getValue(4), // fd
                myBuffer, // buffer
                RegisterFile.getValue(6)); // length
        RegisterFile.updateRegister(2, retValue); // set returned value in register

        // Getting rid of processing exception.  It is the responsibility of the
        // user program to check the syscall's return value.  MARS should not
        // re-emptively terminate MIPS execution because of it.  Thanks to
        // UCLA student Duy Truong for pointing this out.  DPS 28-July-2009
    }
}