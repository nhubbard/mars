package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement

/**
 * Service to exit the MIPS program.
 */
class SyscallExit : AbstractSyscall(10, "Exit") {
    /**
     * Performs syscall function to exit the MIPS program.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        throw ProcessingException() // empty exception list.
    }
}