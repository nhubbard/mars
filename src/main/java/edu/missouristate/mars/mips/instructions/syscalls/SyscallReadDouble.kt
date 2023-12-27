package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.Coprocessor1.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.util.Binary.highOrderLongToInt
import edu.missouristate.mars.util.Binary.lowOrderLongToInt
import edu.missouristate.mars.util.SystemIO

/**
 * Service to read the bits of console input double into $f0 and $f1.
 * $f1 contains the high-order word of the double.
 */
class SyscallReadDouble : AbstractSyscall(7, "ReadDouble") {
    /**
     * Performs syscall function to read the bits of input double into $f0 and $f1.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Higher-numbered register contains high-order word so order is $f1 - $f0.
        val doubleValue: Double = try {
            SystemIO.readDouble(this.number)
        } catch (e: NumberFormatException) {
            throw ProcessingException(
                statement,
                "invalid double input (syscall " + this.number + ")",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
        val longValue = java.lang.Double.doubleToRawLongBits(doubleValue)
        updateRegister(1, highOrderLongToInt(longValue))
        updateRegister(0, lowOrderLongToInt(longValue))
    }
}