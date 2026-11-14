package edu.missouristate.mars.mips.hardware

/** A 128-bit register. Lane zero starts at the least-significant bit of [low]. */
internal data class VectorRegister(val low: Long, val high: Long)
