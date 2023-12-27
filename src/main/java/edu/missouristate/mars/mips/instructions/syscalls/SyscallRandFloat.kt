package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.Coprocessor1.setRegisterToFloat
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import java.util.*

/**
 * Service to return a random floating point value.
 */
class SyscallRandFloat : AbstractSyscall(43, "RandFloat") {
    /**
     * System call to get a float value from the random number generator.
     * Return in $f0 the next pseudorandom, uniformly distributed float value between 0.0 and 1.0
     * from this random number generator's sequence.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Input arguments: $a0 = index of pseudorandom number generator
        // Return: $f0 = the next pseudorandom, uniformly distributed float value between 0.0 and 1.0
        // from this random number generator's sequence.
        val index = getValue(4)
        var stream = RandomStreams.randomStreams[index]
        if (stream == null) {
            stream = Random() // create a non-seeded stream
            RandomStreams.randomStreams[index] = stream
        }
        setRegisterToFloat(0, stream.nextFloat())
    }
}