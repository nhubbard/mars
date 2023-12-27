package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.Binary.intToBinaryString
import edu.missouristate.mars.util.SystemIO

/**
 * Service to display integer stored in $a0 on the console.
 */
class SyscallPrintIntBinary : AbstractSyscall(35, "PrintIntBinary") {
    /**
     * Performs syscall function to print on the console the integer stored in $a0, in hexadecimal format.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        SystemIO.printString(intToBinaryString(getValue(4)))
    }
}