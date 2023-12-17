package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

import java.util.Random;

/**
 * Service to return a random integer.
 */

public class SyscallRandInt extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallRandInt() {
        super(41, "RandInt");
    }

    /**
     * System call to the random number generator.
     * Return in $a0 the next pseudorandom, uniformly distributed int value from this random number generator's sequence.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments: $a0 = index of pseudorandom number generator
        // Return: $a0 = the next pseudorandom, uniformly distributed int value from this random number generator's sequence.
        Integer index = RegisterFile.getValue(4);
        Random stream = (Random) RandomStreams.randomStreams.get(index);
        if (stream == null) {
            stream = new Random(); // create a non-seeded stream
            RandomStreams.randomStreams.put(index, stream);
        }
        RegisterFile.updateRegister(4, stream.nextInt());
    }

}

