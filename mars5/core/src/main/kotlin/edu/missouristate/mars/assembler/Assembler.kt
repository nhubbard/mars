package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.EquivalenceOccurrence
import edu.missouristate.mars.api.AssemblySymbolMap.MacroDefinition
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.core.AssemblyContext
import edu.missouristate.mars.core.MarsContext
import java.nio.file.Path

/** Runs the assembler passes and records the emitted program's source and symbol maps. */
class Assembler(private val context: AssemblyContext) {
    constructor(session: MarsContext) : this(AssemblyContext(session))

    var errorList: ErrorList? = null
        private set
    var sourceMap: InstructionSourceMap = InstructionSourceMap.EMPTY
        private set
    var symbolMap: AssemblySymbolMap = AssemblySymbolMap.EMPTY
        private set

    @Throws(ProcessingException::class)
    @JvmOverloads
    fun assemble(program: MIPSprogram, extended: Boolean, warnings: Boolean = false): ArrayList<ProgramStatement> =
        assemble(arrayListOf(program), extended, warnings)

    @Throws(ProcessingException::class)
    @JvmOverloads
    fun assemble(
        programs: ArrayList<MIPSprogram>, extended: Boolean, warnings: Boolean = false, checkCancellation: Runnable = Runnable {},
    ): ArrayList<ProgramStatement> = context.session.claimStoppedSession().use {
        sourceMap = InstructionSourceMap.EMPTY
        symbolMap = AssemblySymbolMap.EMPTY
        val symbolUses = AssemblySymbolUses()
        val first = FirstPassProcessor(context, checkCancellation, symbolUses)
        val errors = try {
            first.process(programs, extended)
        } catch (failure: ProcessingException) {
            errorList = failure.errors()
            throw failure
        }
        errorList = errors

        val result = SecondPassProcessor(context, checkCancellation, symbolUses).process(programs, errors, warnings)
        val locals = HashMap<Path, Map<String, Definition>>()
        val macros = HashMap<Path, List<MacroDefinition>>()
        val equivalences = HashMap<Path, Set<EquivalenceOccurrence>>()
        for (program in programs) {
            checkCancellation.run()
            val entry = Path.of(program.filename).toAbsolutePath().normalize()
            locals[entry] = definitions(program.localSymbolTable!!, checkCancellation)
            macros[entry] = program.localMacroPool!!.definitions(checkCancellation)
            equivalences[entry] = program.tokenizer!!.equivalenceOccurrences()
        }
        symbolMap = AssemblySymbolMap(
            locals, definitions(context.symbols(), checkCancellation), first.macroCalls, symbolUses.bindings(checkCancellation),
            macros, equivalences,
        )
        sourceMap = result.sourceMap
        result.instructions
    }

    private fun definitions(table: SymbolTable, checkCancellation: Runnable): Map<String, Definition> =
        HashMap<String, Definition>().apply {
            for (symbol in table.allSymbols) {
                checkCancellation.run()
                put(symbol.name, Definition(symbol.address, symbol.kind(), symbol.origin(), symbol.macroPrivate(), symbol.column()))
            }
        }
}
