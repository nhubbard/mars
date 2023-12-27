package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.util.SystemIO

/**
 * Service to close file descriptor given in $a0.
 */
class SyscallClose : AbstractSyscall(16, "Close") {
    /**
     * Performs syscall function to close file descriptor given in $a0.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        SystemIO.closeFile(getValue(4))
    }
}