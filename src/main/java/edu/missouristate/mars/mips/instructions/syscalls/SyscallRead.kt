package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.util.SystemIO

/**
 * Service to read from file descriptor given in $a0. $a1 specifies buffer
 * and $a2 specifies length.
 * The number of characters read is returned in $v0.
 * This was changed from $a0 in MARS 3.7 for SPIM compatibility.
 * The table in COD erroneously shows $a0.
 */
class SyscallRead : AbstractSyscall(14, "Read") {
    /**
     * Performs syscall function to read from file descriptor given in $a0. $a1 specifies buffer
     * and $a2 specifies length.
     * The number of characters read is returned in $v0 (starting MARS 3.7).
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        var byteAddress = getValue(5) // destination of characters read from file
        var index = 0
        val myBuffer = ByteArray(getValue(6))
        val retLength = SystemIO.readFromFile(getValue(4), myBuffer, getValue(6)) // length
        updateRegister(2, retLength) // Set the returned value in the register.
        // Getting rid of processing exception.  It is the responsibility of the
        // user program to check the syscall return value. MARS should not
        // re-emptively terminate MIPS execution because of it.  Thanks to
        // UCLA student Duy Truong for pointing this out.  DPS 28-July-2009
        // Copy bytes from returned buffer into MARS memory.
        try {
            while (index < retLength) Globals.memory.setByte(byteAddress++, myBuffer[index++].toInt())
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
    }
}