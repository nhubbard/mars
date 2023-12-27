package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import edu.missouristate.mars.simulator.Exceptions
import java.util.*

/**
 * Service to return a random integer in a specified range.
 */
class SyscallRandIntRange : AbstractSyscall(42, "RandIntRange") {
    /**
     * System call to the random number generator, with an upper range specified.
     * Return in $a0 the next pseudorandom, uniformly distributed int value between 0 (inclusive)
     * and the specified value (exclusive), drawn from this random number generator's sequence.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments:
        //    $a0 = index of pseudorandom number generator
        //    $a1 = the upper bound for the range of returned values.
        // Return: $a0 = the next pseudorandom, uniformly distributed int value from this
        // random number generator's sequence.
        val index = getValue(4)
        var stream = RandomStreams.randomStreams[index]
        if (stream == null) {
            stream = Random() // create a non-seeded stream
            RandomStreams.randomStreams[index] = stream
        }
        try {
            updateRegister(4, stream.nextInt(getValue(5)))
        } catch (iae: IllegalArgumentException) {
            throw ProcessingException(
                statement,
                "Upper bound of range cannot be negative (syscall $number)",
                Exceptions.SYSCALL_EXCEPTION
            )
        }
    }
}
