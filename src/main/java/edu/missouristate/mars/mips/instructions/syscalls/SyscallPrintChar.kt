package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.SystemIO

/**
 * Service to display character stored in $a0 on the console.
 */
class SyscallPrintChar : AbstractSyscall(11, "PrintChar") {
    /**
     * Performs syscall function to print on the console the character stored in $a0.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // mask off the lower byte of register $a0.
        // Convert to a one-character string and use the string technique.
        val t = (getValue(4) and 0x000000ff).toChar()
        SystemIO.printString(Character.valueOf(t).toString())
    }
}