package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.InvalidRegisterAccessException;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.simulator.Exceptions;

import java.util.Random;

/**
 * Service to return a random floating point value.
 */

public class SyscallRandDouble extends AbstractSyscall {
    /**
     * Build an instance of the syscall with its default service number and name.
     */
    public SyscallRandDouble() {
        super(44, "RandDouble");
    }

    /**
     * System call to the random number generator.
     * Return in $f0 the next pseudorandom, uniformly distributed double value between 0.0 and 1.0
     * from this random number generator's sequence.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        // Input arguments: $a0 = index of pseudorandom number generator
        // Return: $f0 = the next pseudorandom, uniformly distributed double value between 0.0 and 1.0
        // from this random number generator's sequence.
        Integer index = RegisterFile.getValue(4);
        Random stream = (Random) RandomStreams.randomStreams.get(index);
        if (stream == null) {
            stream = new Random(); // create a non-seeded stream
            RandomStreams.randomStreams.put(index, stream);
        }
        try {
            Coprocessor1.setRegisterPairToDouble(0, stream.nextDouble());
        } catch (InvalidRegisterAccessException e) {   // register ID error in this method
            throw new ProcessingException(statement,
                    "Internal error storing double to register (syscall " + this.getNumber() + ")",
                    Exceptions.SYSCALL_EXCEPTION);
        }
    }

}
