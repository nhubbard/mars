package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.util.SystemIO

/**
 * Service to read an integer from input console into $v0.
 */
class SyscallReadInt : AbstractSyscall(5, "ReadInt") {
    /**
     * Performs syscall function to read an integer from input console into $v0
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        val value = try {
            SystemIO.readInteger(this.number)
        } catch (e: NumberFormatException) {
            throw ProcessingException(
                statement,
                "invalid integer input (syscall $number)",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
        updateRegister(2, value)
    }
}