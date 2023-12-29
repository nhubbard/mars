package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.bitsToDouble
import edu.missouristate.mars.mips.hardware.Coprocessor1.getValue
import edu.missouristate.mars.util.Binary.twoIntegersToLong
import edu.missouristate.mars.util.SystemIO

/**
 * Service to display double whose bits are stored in $f12 & $f13 onto the console.
 * $f13 contains high-order word of the double.
 */
class SyscallPrintDouble : AbstractSyscall(3, "PrintDouble") {
    /**
     * Performs syscall function to print double whose bits are stored in $f12 & $f13.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Note: Higher-numbered register contains the high-order word, so concat 13-12.
        SystemIO.printString(
            twoIntegersToLong(getValue(13), getValue(12)).bitsToDouble().toString()
        )
    }
}