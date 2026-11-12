package edu.missouristate.mars.core

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblyResult
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.MarsAssembler
import java.nio.file.Path

/** Public assembly API backed by one session, including warnings and source positions. */
class SessionAssembler(private val session: MarsContext) : MarsAssembler {
    override fun assemble(sources: List<Path>): AssemblyResult = assemble(
        sources, null, session.settings.isExtendedAssemblerEnabled, session.settings.isWarningsAreErrors,
    )

    override fun assemble(
        sources: List<Path>,
        exceptionHandler: Path?,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean,
    ): AssemblyResult = assemble {
        session.assemble(sources, exceptionHandler, extendedAssemblerEnabled, warningsAreErrors)
    }

    override fun assembleSnapshot(
        snapshot: AssemblySourceSnapshot,
        exceptionHandler: Path?,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean,
        checkCancellation: Runnable,
    ): AssemblyResult = assemble {
        session.assembleSnapshot(snapshot, exceptionHandler, extendedAssemblerEnabled, warningsAreErrors, checkCancellation)
    }

    private inline fun assemble(assembleProgram: () -> MIPSprogram): AssemblyResult = try {
        val program = assembleProgram()
        AssemblyResult(program, diagnostics(program.assemblyDiagnostics))
    } catch (error: ProcessingException) {
        AssemblyResult(null, diagnostics(error.errors()))
    }

    private fun diagnostics(errors: ErrorList): List<AssemblyError> = errors.errorMessages.map { message ->
        val filename = message.filename
        AssemblyError(
            if (filename.isNullOrEmpty()) null else Path.of(filename),
            message.line, message.position, message.message, message.isWarning,
        )
    }
}
