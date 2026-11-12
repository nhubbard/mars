package edu.missouristate.mars.api

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmRecord

/** An executed instruction, including its transfer and branch decision if available. */
@JvmRecord
data class StepEvent @JvmOverloads constructor(
    val pc: Int,
    val instruction: Int,
    val stepCount: Long,
    val transfer: Transfer? = null,
    val branchOutcome: BranchOutcome? = null,
) {
    enum class BranchOutcome {
        TAKEN,
        NOT_TAKEN,
    }

    sealed interface Transfer

    /** An executed jump or taken branch saved a return address in a nonzero register. */
    @JvmRecord
    data class Call(val linkRegister: Int, val returnAddress: Int) : Transfer

    /** A non-linking jump reached its target after any delay slot. */
    @JvmRecord
    data class Jump(val target: Int) : Transfer

    /** A recoverable fault or interrupt entered the exception handler. */
    enum class ExceptionEntry : Transfer {
        INSTANCE,
    }

    /** ERET restored execution from the exception program counter. */
    enum class ExceptionReturn : Transfer {
        INSTANCE,
    }
}
