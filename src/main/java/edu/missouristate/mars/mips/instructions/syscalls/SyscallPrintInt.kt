package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.SystemIO

/**
 * Service to display integer stored in $a0 on the console.
 */
class SyscallPrintInt : AbstractSyscall(1, "PrintInt") {
    /**
     * Performs syscall function to print on the console the integer stored in $a0.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        SystemIO.printString(getValue(4).toString())
    }
}