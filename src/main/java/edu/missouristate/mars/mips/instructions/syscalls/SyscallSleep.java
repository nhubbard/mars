package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;

/**
 * Service to cause the MARS Java thread to sleep for (at least) the specified number of milliseconds.
 * This timing will not be precise as the Java implementation will add some overhead.
 */

public class SyscallSleep extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallSleep() {
        super(32, "Sleep");
    }

    /**
     * System call to cause the MARS Java thread to sleep for (at least) the specified number of milliseconds.
     * This timing will not be precise as the Java implementation will add some overhead.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments: $a0 is the length of time to sleep in milliseconds.

        try {
            Thread.sleep(RegisterFile.getValue(4)); // units of milliseconds  1000 millisec = 1 sec.
        } catch (InterruptedException e) {
            return; // no exception handling
        }
    }

}
