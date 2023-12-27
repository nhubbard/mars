package edu.missouristate.mars.mips.instructions.syscalls

import java.util.*

/**
 * This small class serves only to hold a static HashMap for storing
 * random number generators for use by all the random number generator
 * syscalls.
 */
object RandomStreams {
    /**
     * Collection of pseudorandom number streams available for use in Rand-type syscalls.
     * The streams are not seeded by default.
     */
    @JvmField
    val randomStreams: HashMap<Int, Random> = HashMap()
}