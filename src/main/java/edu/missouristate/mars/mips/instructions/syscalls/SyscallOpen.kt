package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.util.SystemIO

/**
 * Service to open file name specified by $a0. File descriptor returned in $v0.
 * (this was changed from $a0 in MARS 3.7 for SPIM compatibility.  The table
 * in COD erroneously shows $a0).
 */
class SyscallOpen : AbstractSyscall(13, "Open") {
    /**
     * Performs syscall function to open file name specified by $a0. File descriptor returned
     * in $v0.  Only supported flags ($a1) are read-only (0), write-only (1) and
     * write-append (9). write-only flag creates file if it does not exist, so it is technically
     * write-create.  write-append will start writing at end of existing file.
     * Mode ($a2) is ignored.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
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
        var filename = ""
        var byteAddress = getValue(4)
        val ch = charArrayOf(' ')
        try {
            ch[0] = Globals.memory.getByte(byteAddress).toChar()
            while (ch[0].code != 0) {
                filename += String(ch)
                byteAddress++
                ch[0] = Globals.memory.getByte(byteAddress).toChar()
            }
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
        val retValue = SystemIO.openFile(filename, getValue(5))
        updateRegister(2, retValue) // set returned fd value in register
        // Getting rid of the ProcessingException.
        // It is the responsibility of the user's program to check for an error.
        // MARS should not pre-emptively terminate MIPS execution because of it.
        // Thanks to UCLA student Duy Truong for pointing this out.  DPS 28-July-2009.
    }
}