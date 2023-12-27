package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.util.SystemIO

/**
 * Service to read a character from input console into $a0.
 */
class SyscallReadChar : AbstractSyscall(12, "ReadChar") {
    /**
     * Performs syscall function to read a character from input console into $a0
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        val value = try {
            SystemIO.readChar(number)
        } catch (e: IndexOutOfBoundsException) {
            throw ProcessingException(
                statement,
                "invalid char input (syscall $number)",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
        // DPS 20 June 2008: changed from 4 ($a0) to 2 ($v0)
        updateRegister(2, value)
    }
}