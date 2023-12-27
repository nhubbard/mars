package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import edu.missouristate.mars.mips.hardware.RegisterFile.updateRegister
import java.util.*

/**
 * Service to return a random integer.
 */
class SyscallRandInt : AbstractSyscall(41, "RandInt") {
    /**
     * System call to get a random integer from the random number generator.
     * Return in $a0 the next pseudorandom, uniformly distributed int value from this random number generator's sequence.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments: $a0 = index of pseudorandom number generator
        // Return: $a0 = the next pseudorandom, uniformly distributed int value from this random number generator's sequence.
        val index = getValue(4)
        var stream = RandomStreams.randomStreams[index]
        if (stream == null) {
            stream = Random() // create a non-seeded stream
            RandomStreams.randomStreams[index] = stream
        }
        updateRegister(4, stream.nextInt())
    }
}

