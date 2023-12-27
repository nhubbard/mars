package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.Binary.unsignedIntToIntString
import edu.missouristate.mars.util.SystemIO

/**
 * Service to display integer stored in $a0 on the console as unsigned decimal.
 */
class SyscallPrintIntUnsigned : AbstractSyscall(36, "PrintIntUnsigned") {
    /**
     * Performs syscall function to print on the console the integer stored in $a0.
     * The value is treated as unsigned.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        SystemIO.printString(unsignedIntToIntString(getValue(4)))
    }
}