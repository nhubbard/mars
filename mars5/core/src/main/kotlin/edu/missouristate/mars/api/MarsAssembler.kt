package edu.missouristate.mars.api

import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.jvm.JvmSuppressWildcards

/** Assembles one or more ordered MIPS source files into an executable program. */
@JvmSuppressWildcards
interface MarsAssembler {
    /** The first file in [sources] is the program entry point. */
    fun assemble(sources: List<Path>): AssemblyResult

    /** Assemble sources with an optional exception handler and the requested assembler settings. */
    fun assemble(
        sources: List<Path>,
        exceptionHandler: Path?,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean,
    ): AssemblyResult

    /** Assemble captured source text. Missing includes produce assembly diagnostics. */
    fun assembleSnapshot(
        snapshot: AssemblySourceSnapshot,
        exceptionHandler: Path?,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean,
    ): AssemblyResult = assembleSnapshot(snapshot, exceptionHandler, extendedAssemblerEnabled, warningsAreErrors) {}

    /**
     * Assemble captured source text with cooperative cancellation on the calling thread.
     * [checkCancellation] may throw [CancellationException]. Use a separate session to retain
     * a previous program across cancellation.
     */
    fun assembleSnapshot(
        snapshot: AssemblySourceSnapshot,
        exceptionHandler: Path?,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean,
        checkCancellation: Runnable,
    ): AssemblyResult
}
