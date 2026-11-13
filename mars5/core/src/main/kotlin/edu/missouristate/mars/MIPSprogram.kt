package edu.missouristate.mars

import edu.missouristate.mars.api.AssembledInstruction
import edu.missouristate.mars.api.AssemblySourceReader
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.Program
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.assembler.Assembler
import edu.missouristate.mars.assembler.MacroPool
import edu.missouristate.mars.assembler.SourceLine
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.assembler.TokenList
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.core.AssemblyContext
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.simulator.BackStepper
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.nio.file.Path
import java.util.List.copyOf as copyList
import java.util.concurrent.CancellationException
import java.util.function.LongConsumer
import kotlin.jvm.JvmRecord

/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Connects a session's written source, assembly metadata, and executable instructions. */
open class MIPSprogram @JvmOverloads constructor(
    private val session: MarsContext,
    private val sourceSnapshot: AssemblySourceSnapshot? = null,
) : Program {
    private val assemblyContext = AssemblyContext(session)
    private var sourceMap = InstructionSourceMap.EMPTY
    private var symbolMap = AssemblySymbolMap.EMPTY
    private var syscallEnvironment: SyscallEnvironment? = null
    private var sessionExecution: InstructionContext? = null
    private var assemblyBackStepper: BackStepper? = null

    private var diagnostics: ErrorList? = null
    open val assemblyDiagnostics: ErrorList? get() = diagnostics
    private var sourceFilename: String? = null
    open val filename: String? get() = sourceFilename
    private var writtenLines: ArrayList<String>? = null
    open val sourceList: ArrayList<String>? get() = writtenLines
    private var physicalLines: ArrayList<SourceLine>? = null
    private var tokenizedLines: ArrayList<TokenList>? = null
    open val tokenList: ArrayList<TokenList>? get() = tokenizedLines
    private var activeTokenizer: Tokenizer? = null
    open val tokenizer: Tokenizer? get() = activeTokenizer
    private var parsedStatements: ArrayList<ProgramStatement>? = null
    open val parsedList: ArrayList<ProgramStatement>? get() = parsedStatements
    private var instructions: ArrayList<ProgramStatement>? = null
    open val machineList: ArrayList<ProgramStatement>? get() = instructions
    private var symbols: SymbolTable? = null
    open val localSymbolTable: SymbolTable? get() = symbols
    open var localMacroPool: MacroPool? = null

    open fun assemblyContext(): AssemblyContext = assemblyContext

    open fun newErrorList(): ErrorList = assemblyContext.newErrorList()

    open fun createRelatedProgram(): MIPSprogram = MIPSprogram(session, sourceSnapshot)

    open var sourceLineList: ArrayList<SourceLine>?
        get() = physicalLines
        set(lines) {
            physicalLines = lines
            writtenLines = lines!!.mapTo(ArrayList(lines.size)) { it.source }
        }

    /** Identifies a source and its relative-include directory without changing the display path. */
    open val includeIdentity: Path
        get() {
            val path = Path.of(sourceFilename!!).toAbsolutePath()
            if (sourceSnapshot != null) return sourceSnapshot.includeIdentity(path)
            return try {
                // Relative includes still start beside a source that is a symlink.
                AssemblySourceReader.resolveIncludeDirectory(path.parent).resolve(path.fileName)
            } catch (_: IOException) {
                // The source may have been removed since it was read.
                path.normalize()
            }
        }

    open fun createParsedList(): ArrayList<ProgramStatement> =
        ArrayList<ProgramStatement>().also { parsedStatements = it }

    open val backStepper: BackStepper?
        get() = if (session.program === this) assemblyBackStepper else null

    open fun backSteppingEnabled(): Boolean = backStepper?.enabled() == true

    /** Returns a source line using one-based numbering. */
    open fun getSourceLine(line: Int): String? = if (line < 1) null else writtenLines!!.getOrNull(line - 1)

    @Throws(ProcessingException::class)
    open fun readSource(file: String) {
        readSource(file) {}
    }

    @Throws(ProcessingException::class)
    open fun readSource(file: String, checkCancellation: Runnable) {
        readSource(file, checkCancellation, include = false)
    }

    @Throws(ProcessingException::class)
    open fun readIncludedSource(file: String, checkCancellation: Runnable) {
        readSource(file, checkCancellation, include = true)
    }

    private fun readSource(file: String, checkCancellation: Runnable, include: Boolean) {
        sourceFilename = file
        val lines = ArrayList<String>()
        writtenLines = lines
        physicalLines = null
        try {
            openSource(file, include).buffered().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    checkCancellation.run()
                    lines.add(line)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val errors = newErrorList()
            errors.add(ErrorMessage(if (sourceSnapshot == null) null else this, 0, 0, failure.toString()))
            throw ProcessingException(errors)
        }
    }

    private fun openSource(file: String, include: Boolean): Reader {
        val requested = Path.of(file)
        if (sourceSnapshot == null) {
            return if (include) AssemblySourceReader.openIncluded(requested) else AssemblySourceReader.open(requested)
        }
        val path = if (include) sourceSnapshot.resolveInclude(requested) else requested.toAbsolutePath().normalize()
        val text = path?.let(sourceSnapshot.texts()::get) ?: throw FileNotFoundException("Source was not captured: $file")
        sourceFilename = path.toString()
        return StringReader(text)
    }

    @Throws(ProcessingException::class)
    open fun tokenize() {
        tokenize {}
    }

    private fun tokenize(checkCancellation: Runnable) {
        val current = assemblyContext.tokenizer(this)
        activeTokenizer = current
        tokenizedLines = current.tokenize(this, checkCancellation)
        symbols = SymbolTable(assemblyContext.symbols())
    }

    /** Tokenizes each source, with an optional exception handler before the lead file. */
    @Throws(ProcessingException::class)
    open fun prepareFilesForAssembly(
        filenames: ArrayList<String>, leadFilename: String?, exceptionHandler: String?,
    ): ArrayList<MIPSprogram> = prepareFilesForAssembly(filenames, leadFilename, exceptionHandler) {}

    @Throws(ProcessingException::class)
    open fun prepareFilesForAssembly(
        filenames: ArrayList<String>, leadFilename: String?, exceptionHandler: String?, checkCancellation: Runnable,
    ): ArrayList<MIPSprogram> {
        val programs = ArrayList<MIPSprogram>()
        val leadPosition = if (!exceptionHandler.isNullOrEmpty()) {
            filenames.addFirst(exceptionHandler)
            1
        } else 0
        for (file in filenames) {
            checkCancellation.run()
            val program = if (file == leadFilename) this else createRelatedProgram()
            program.readSource(file, checkCancellation)
            program.tokenize(checkCancellation)
            if (program === this && programs.isNotEmpty()) programs.add(leadPosition, program) else programs.add(program)
        }
        return programs
    }

    @Throws(ProcessingException::class)
    open fun assemble(programs: ArrayList<MIPSprogram>?, extended: Boolean): ErrorList = assemble(programs, extended, false)

    @Throws(ProcessingException::class)
    open fun assemble(programs: ArrayList<MIPSprogram>?, extended: Boolean, warnings: Boolean): ErrorList =
        assemble(programs, extended, warnings) {}

    @Throws(ProcessingException::class)
    open fun assemble(
        programs: ArrayList<MIPSprogram>?, extended: Boolean, warnings: Boolean, checkCancellation: Runnable,
    ): ErrorList = session.claimStoppedSession().use {
        require(!programs.isNullOrEmpty()) { "At least one source file is required" }
        require(programs.all { assemblyContext.sharesSession(it.assemblyContext()) }) {
            "All source files must belong to the program's session"
        }
        assemblyBackStepper = null
        val assembler = Assembler(assemblyContext)
        instructions = assembler.assemble(programs, extended, warnings, checkCancellation)
        sourceMap = assembler.sourceMap
        symbolMap = assembler.symbolMap
        checkCancellation.run()
        assemblyBackStepper = session.backStepper
        sessionExecution = null
        assemblyContext.finishAssembly(this)
        assembler.errorList!!.also { diagnostics = it }
    }

    @Throws(ProcessingException::class)
    open fun simulate(breakPoints: IntArray?): Boolean = simulateFromPC(breakPoints, -1)

    @Throws(ProcessingException::class)
    open fun simulate(maxSteps: Int): Boolean = simulateFromPC(null, maxSteps)

    /** The observer sees completed instructions, including a normal exit syscall. */
    @Throws(ProcessingException::class)
    open fun simulate(maxSteps: Int, instructionCount: LongConsumer): Boolean =
        simulateSession(null, maxSteps, instructionCount)

    @Throws(ProcessingException::class)
    open fun simulateFromPC(breakPoints: IntArray?, maxSteps: Int): Boolean = simulateSession(breakPoints, maxSteps, null)

    @Throws(ProcessingException::class)
    open fun simulateStepAtPC(): Boolean = simulateSession(null, 1, null)

    /** Sets session I/O while execution is stopped. */
    open fun setSyscallEnvironment(environment: SyscallEnvironment) {
        session.claimStoppedSession().use {
            syscallEnvironment = environment
            sessionExecution = null
        }
    }

    open val exitCode: Int get() = sessionExecution?.exitCode ?: 0

    override fun instructionCount(): Int = instructions?.size ?: 0

    override fun sourceMap(): InstructionSourceMap = sourceMap

    override fun instructionsAt(location: Location, checkCancellation: Runnable): List<AssembledInstruction> {
        val instructions = ArrayList<AssembledInstruction>()
        for (statement in this.instructions ?: return emptyList()) {
            checkCancellation.run()
            val origin = sourceMap.origins()[statement.address]
            var frame = origin
            while (frame != null) {
                if (frame.location == location) {
                    instructions.add(assembledInstruction(statement, origin))
                    break
                }
                frame = frame.caller
            }
        }
        return copyList(instructions)
    }

    override fun pseudoInstructions(checkCancellation: Runnable): List<AssembledInstruction> {
        val addresses = sourceMap.pseudoAddresses()
        if (addresses.isEmpty()) return emptyList()
        val instructions = ArrayList<AssembledInstruction>(addresses.size)
        for (statement in this.instructions!!) {
            checkCancellation.run()
            val address = statement.address
            if (address in addresses) instructions.add(assembledInstruction(statement, sourceMap.origins()[address]))
        }
        return copyList(instructions)
    }

    private fun assembledInstruction(statement: ProgramStatement, origin: Origin?): AssembledInstruction =
        AssembledInstruction(statement.address, statement.binaryStatement, statement.printableBasicAssemblyStatement, origin)

    override fun symbolMap(): AssemblySymbolMap = symbolMap

    /** A null stop reason means execution can continue. */
    @JvmRecord
    data class ExecutionStep @JvmOverloads constructor(
        val pc: Int,
        val instruction: Int,
        val executed: Boolean,
        val stopReason: StopEvent.Reason?,
        val transfer: StepEvent.Transfer? = null,
        val branchOutcome: StepEvent.BranchOutcome? = null,
    )

    @Throws(ProcessingException::class)
    open fun executeSessionInstruction(): ExecutionStep =
        session.claimExecution().use { AccessOrigin.duringExecution<ExecutionStep, ProcessingException>(::executeSessionInstructionStopped) }

    private fun executeSessionInstructionStopped(): ExecutionStep {
        check(session.program === this && instructions != null) { "This is not the current assembled program of its session" }
        val execution = sessionExecution ?: InstructionContext(session, syscallEnvironment ?: session.syscallEnvironment)
            .also { sessionExecution = it }
        val pc = session.registerFile.programCounter
        val statement = try {
            session.memory.fetchInstruction(pc)
        } catch (error: AddressErrorException) {
            val location = ProgramStatement(0, pc, session.instructionSet)
            val recording = session.backStepper.beginInstruction(pc, location)
            try {
                session.registerFile.incrementPC()
                throw ProcessingException(execution, location, error)
            } finally {
                if (recording) session.backStepper.endInstruction()
            }
        }
        if (statement == null) {
            session.delayedBranch.clear()
            return ExecutionStep(pc, 0, false, StopEvent.Reason.CLIFF_TERMINATION)
        }
        try {
            execution.executeWithExceptionHandler(statement)
        } catch (error: ProcessingException) {
            session.delayedBranch.clear()
            if (error.errors() != null) throw error
            return ExecutionStep(pc, statement.binaryStatement, true, StopEvent.Reason.NORMAL_EXIT)
        }
        return ExecutionStep(pc, statement.binaryStatement, true, null, execution.lastTransfer(), execution.lastBranchOutcome())
    }

    // A nonpositive limit means execution is unlimited.
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun simulateSession(breakPoints: IntArray?, maxSteps: Int, instructionCount: LongConsumer?): Boolean =
        session.claimStoppedSession().use {
            var steps = 0L
            while (maxSteps <= 0 || steps < maxSteps) {
                if (Thread.currentThread().isInterrupted || session.isClosing) return false
                val result = executeSessionInstruction()
                if (result.executed) {
                    steps++
                    instructionCount?.accept(steps)
                }
                if (result.stopReason != null) return true
                if (breakPoints != null) {
                    for (breakpoint in breakPoints) {
                        if (breakpoint == session.registerFile.programCounter) return false
                    }
                }
            }
            false
        }

    open fun createMacroPool(): MacroPool = MacroPool(this).also { localMacroPool = it }

    companion object {
        /** Creates the execution handle for an already assembled image. */
        @JvmStatic
        fun fromImage(
            session: MarsContext, filename: String, instructions: ArrayList<ProgramStatement>,
            sourceMap: InstructionSourceMap, symbolMap: AssemblySymbolMap,
        ): MIPSprogram = MIPSprogram(session).apply {
            sourceFilename = filename
            this.instructions = instructions
            this.sourceMap = sourceMap
            this.symbolMap = symbolMap
            symbols = SymbolTable(session.symbolTable).also {
                it.loadDefinitions(symbolMap.locals()[Path.of(filename)].orEmpty())
            }
            assemblyBackStepper = session.backStepper
        }
    }
}
