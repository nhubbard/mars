package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.util.SystemIO;

/**
 * Service to open file name specified by $a0. File descriptor returned in $v0.
 * (this was changed from $a0 in MARS 3.7 for SPIM compatibility.  The table
 * in COD erroneously shows $a0).
 */

public class SyscallOpen extends AbstractSyscall {
    /**
     * Build an instance of the Open file syscall.  Default service number
     * is 13 and name is "Open".
     */
    public SyscallOpen() {
        super(13, "Open");
    }

    /**
     * Performs syscall function to open file name specified by $a0. File descriptor returned
     * in $v0.  Only supported flags ($a1) are read-only (0), write-only (1) and
     * write-append (9). write-only flag creates file if it does not exist, so it is technically
     * write-create.  write-append will start writing at end of existing file.
     * Mode ($a2) is ignored.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // NOTE: with MARS 3.7, return changed from $a0 to $v0 and the terminology
        // of 'flags' and 'mode' was corrected (they had been reversed).
        //
        // Arguments: $a0 = filename (string), $a1 = flags, $a2 = mode
        // Result: file descriptor (in $v0)
        // This code implements the flags:
        // Read          flag = 0
        // Write         flag = 1
        // Read/Write    NOT IMPLEMENTED
        // Write/append  flag = 9
        // This code implements the modes:
        // NO MODES IMPLEMENTED  -- MODE IS IGNORED
        // Returns in $v0: a "file descriptor" in the range 0 to SystemIO.SYSCALL_MAXFILES-1,
        // or -1 if error
        String filename = "";
        int byteAddress = RegisterFile.getValue(4);
        char[] ch = {' '}; // Need an array to convert to String
        try {
            ch[0] = (char) Globals.memory.getByte(byteAddress);
            while (ch[0] != 0) // only uses single location ch[0]
            {
                filename = filename.concat(new String(ch)); // parameter to String constructor is a char[] array
                byteAddress++;
                ch[0] = (char) Globals.memory.getByte(
                        byteAddress);
            }
        } catch (AddressErrorException e) {
            throw new ProcessingException(statement, e);
        }
        int retValue = SystemIO.openFile(filename,
                RegisterFile.getValue(5));
        RegisterFile.updateRegister(2, retValue); // set returned fd value in register

        // GETTING RID OF PROCESSING EXCEPTION.  IT IS THE RESPONSIBILITY OF THE
        // USER PROGRAM TO CHECK FOR BAD FILE OPEN.  MARS SHOULD NOT PRE-EMPTIVELY
        // TERMINATE MIPS EXECUTION BECAUSE OF IT.  Thanks to UCLA student
        // Duy Truong for pointing this out.  DPS 28-July-2009.
    }
}