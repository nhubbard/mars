package edu.missouristate.mars.api

/** A frontend memory operation used an invalid or misaligned byte address. */
open class MemoryAccessException(open val address: Int, message: String?) : Exception(message)
