package edu.missouristate.mars.api

import edu.missouristate.mars.api.InstructionSourceMap.Origin
import kotlin.jvm.JvmRecord

/** An emitted instruction and its source provenance when available. */
@JvmRecord
data class AssembledInstruction(val address: Int, val encoding: Int, val assembly: String, val origin: Origin?)
