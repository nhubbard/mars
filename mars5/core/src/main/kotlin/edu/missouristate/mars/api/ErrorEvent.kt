package edu.missouristate.mars.api

import kotlin.jvm.JvmRecord

/** A runtime error that halted the simulation. */
@JvmRecord
data class ErrorEvent(val message: String?, val cause: Throwable?)
