package edu.missouristate.mars.api

import java.util.List.copyOf

/** The program and its diagnostics, or an absent program when assembly failed. */
class AssemblyResult(private val program: Program?, errors: List<AssemblyError>) {
    private val errors: List<AssemblyError> = copyOf(errors)

    fun program(): Program? = program

    fun errors(): List<AssemblyError> = errors

    fun succeeded(): Boolean = program != null && errors.all { it.isWarning }

    override fun equals(other: Any?): Boolean =
        other is AssemblyResult && program == other.program && errors == other.errors

    override fun hashCode(): Int = 31 * program.hashCode() + errors.hashCode()

    override fun toString(): String = "AssemblyResult[program=$program, errors=$errors]"
}
