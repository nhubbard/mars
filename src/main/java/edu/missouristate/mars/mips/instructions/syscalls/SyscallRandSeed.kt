package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue
import java.util.*

/**
 * Service to set seed for the underlying Java pseudorandom number generator. No values are returned.
 */
class SyscallRandSeed : AbstractSyscall(40, "RandSeed") {
    /**
     * Set the seed of the underlying Java pseudorandom number generator.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        // Arguments: $a0 = index of pseudorandom number generator
        //   $a1 = seed for pseudorandom number generator.
        // Result: No values are returned. Set the seed of the underlying Java pseudorandom number generator.
        val index = getValue(4)
        val stream = RandomStreams.randomStreams[index]
        if (stream == null) {
            RandomStreams.randomStreams[index] = Random(getValue(5).toLong())
        } else {
            stream.setSeed(getValue(5).toLong())
        }
    }
}