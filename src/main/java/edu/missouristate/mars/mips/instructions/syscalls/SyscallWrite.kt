package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.util.SystemIO

/**
 * Service to write to file descriptor given in $a0.
 * $a1 specifies buffer and $a2 specifies length.
 * The number of characters written is returned in $v0.
 * This was changed from $a0 in MARS 3.7 for SPIM compatibility.
 * The table in COD erroneously shows $a0.
 */
class SyscallWrite : AbstractSyscall(15, "Write") {
    /**
     * Performs syscall function to write to file descriptor given in $a0.
     * $a1 specifies buffer and $a2 specifies length.
     * The number of characters written is returned in $v0, starting with MARS 3.7.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        var byteAddress = getValue(5) // source of characters to write to file
        var b: Byte
        val reqLength = getValue(6) // user-requested length
        var index = 0
        val myBuffer = ByteArray(getValue(6) + 1) // specified length plus null termination
        try {
            b = Globals.memory.getByte(byteAddress).toByte()
            // Stop at the requested length. Null bytes are included.
            while (index < reqLength) {
                myBuffer[index++] = b
                byteAddress++
                b = Globals.memory.getByte(byteAddress).toByte()
            }
            myBuffer[index] = 0 // Add string termination
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
        val retValue = SystemIO.writeToFile(getValue(4), myBuffer, getValue(6))
        updateRegister(2, retValue) // set returned value in register

        // Getting rid of processing exception.  It is the responsibility of the
        // user program to check the syscall's return value.  MARS should not
        // re-emptively terminate MIPS execution because of it.  Thanks to
        // UCLA student Duy Truong for pointing this out.  DPS 28-July-2009
    }
}