package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.simulator.Exceptions;

import java.util.Random;

/**
 * Service to return a random integer in a specified range.
 */

public class SyscallRandIntRange extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallRandIntRange() {
        super(42, "RandIntRange");
    }

    /**
     * System call to the random number generator, with an upper range specified.
     * Return in $a0 the next pseudorandom, uniformly distributed int value between 0 (inclusive)
     * and the specified value (exclusive), drawn from this random number generator's sequence.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments:
        //    $a0 = index of pseudorandom number generator
        //    $a1 = the upper bound of range of returned values.
        // Return: $a0 = the next pseudorandom, uniformly distributed int value from this
        // random number generator's sequence.
        Integer index = RegisterFile.getValue(4);
        Random stream = (Random) RandomStreams.randomStreams.get(index);
        if (stream == null) {
            stream = new Random(); // create a non-seeded stream
            RandomStreams.randomStreams.put(index, stream);
        }
        try {
            RegisterFile.updateRegister(4, stream.nextInt(RegisterFile.getValue(5)));
        } catch (IllegalArgumentException iae) {
            throw new ProcessingException(statement,
                    "Upper bound of range cannot be negative (syscall " + this.getNumber() + ")",
                    Exceptions.SYSCALL_EXCEPTION);
        }
    }

}
