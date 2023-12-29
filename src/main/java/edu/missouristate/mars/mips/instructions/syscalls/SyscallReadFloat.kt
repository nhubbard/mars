package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.Coprocessor1.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.toRawIntBits
import edu.missouristate.mars.util.SystemIO

/**
 * Service to read the bits of input float into $f0
 */
class SyscallReadFloat : AbstractSyscall(6, "ReadFloat") {
    /**
     * Performs syscall function to read the bits of input float into $f0
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        val floatValue = try {
            SystemIO.readFloat(this.number)
        } catch (e: NumberFormatException) {
            throw ProcessingException(
                statement,
                "invalid float input (syscall $number)",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
        updateRegister(0, floatValue.toRawIntBits())
    }
}