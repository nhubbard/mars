package edu.missouristate.mars.mips.instructions.syscalls;

import java.util.HashMap;


/**
 * This small class serves only to hold a static HashMap for storing
 * random number generators for use by all the random number generator
 * syscalls.
 */

public class RandomStreams {
    /**
     * Collection of pseudorandom number streams available for use in Rand-type syscalls.
     * The streams are by default not seeded.
     */
    static final HashMap randomStreams = new HashMap();
}