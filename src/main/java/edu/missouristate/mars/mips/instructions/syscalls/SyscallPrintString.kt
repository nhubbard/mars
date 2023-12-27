package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.SystemIO

/**
 * Service to display string stored starting at address in $a0 onto the console.
 */
class SyscallPrintString : AbstractSyscall(4, "PrintString") {
    /**
     * Performs syscall function to print string stored starting at address in $a0.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        var byteAddress = getValue(4)
        var ch: Char
        try {
            ch = Globals.memory.getByte(byteAddress).toChar()
            // Won't stop until a null (\0) byte is reached
            while (ch.code != 0) {
                SystemIO.printString(Character.valueOf(ch).toString())
                byteAddress++
                ch = Globals.memory.getByte(byteAddress).toChar()
            }
        } catch (e: AddressErrorException) {
            throw ProcessingException(statement, e)
        }
    }
}