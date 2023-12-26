package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.ErrorList

/**
 * Represents attempt to access double precision register using an odd (e.g., $f1, $f23) register name.
 */
class InvalidRegisterAccessException : Exception() {
    private val errs: ErrorList? = null
}
