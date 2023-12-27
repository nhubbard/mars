package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue

/**
 * Service to cause the MARS Java thread to sleep for (at least) the specified number of milliseconds.
 * This timing will not be precise as the Java implementation will add some overhead.
 */
class SyscallSleep : AbstractSyscall(32, "Sleep") {
    /**
     * System call to cause the MARS Java thread to sleep for (at least) the specified number of milliseconds.
     * This timing will not be precise as the Java implementation will add some overhead.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments: $a0 is the length of time to sleep in milliseconds.
        try {
            Thread.sleep(getValue(4).toLong()) // units of milliseconds: 1000 ms = 1 s
        } catch (e: InterruptedException) {
            // no exception handling
        }
    }
}
