package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.Globals
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.simulator.Exceptions

/**
 * Service to allocate a fixed amount of heap memory specified in $a0, putting address into $v0.
 */
class SyscallSbrk : AbstractSyscall(9, "Sbrk") {
    /**
     * Performs syscall function to allocate a fixed amount of heap memory specified in $a0, putting address into $v0.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        val address = try {
            Globals.memory.allocateBytesFromHeap(getValue(4))
        } catch (iae: IllegalArgumentException) {
            throw ProcessingException(
                statement,
                "${iae.message} (syscall $number)",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
        updateRegister(2, address)
    }
}