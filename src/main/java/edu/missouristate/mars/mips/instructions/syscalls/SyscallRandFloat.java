package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import java.util.Random;

/**
 * Service to return a random floating point value.
 */

public class SyscallRandFloat extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallRandFloat() {
        super(43, "RandFloat");
    }

    /**
     * System call to the random number generator.
     * Return in $f0 the next pseudorandom, uniformly distributed float value between 0.0 and 1.0
     * from this random number generator's sequence.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments: $a0 = index of pseudorandom number generator
        // Return: $f0 = the next pseudorandom, uniformly distributed float value between 0.0 and 1.0
        // from this random number generator's sequence.
        Integer index = RegisterFile.getValue(4);
        Random stream = (Random) RandomStreams.randomStreams.get(index);
        if (stream == null) {
            stream = new Random(); // create a non-seeded stream
            RandomStreams.randomStreams.put(index, stream);
        }
        Coprocessor1.setRegisterToFloat(0, stream.nextFloat());
    }
}