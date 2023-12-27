package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.Coprocessor1.getValue
import edu.missouristate.mars.util.SystemIO

/**
 * Service to display on the console float whose bits are stored in $f12
 */
class SyscallPrintFloat : AbstractSyscall(2, "PrintFloat") {
    /**
     * Performs syscall function to display float whose bits are stored in $f12
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        SystemIO.printString(java.lang.Float.intBitsToFloat(getValue(12)).toString())
    }
}