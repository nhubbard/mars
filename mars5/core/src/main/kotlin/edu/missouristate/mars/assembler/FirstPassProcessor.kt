package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.AssemblySymbolMap.Kind
import edu.missouristate.mars.api.AssemblySymbolMap.MacroCall
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.core.AssemblyContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.util.Binary
import kotlin.math.pow

/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

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

/** Builds symbol tables, applies directives, and resolves forward data references. */
internal class FirstPassProcessor(
    val context: AssemblyContext,
    val checkCancellation: Runnable,
    private val symbolUses: AssemblySymbolUses,
) {
    val macroCalls = HashMap<SourceToken, MutableSet<MacroCall>>()
    private val parser = Parser(this)

    lateinit var errors: ErrorList
        private set
    var inDataSegment = false
        private set
    var inMacroSegment = false
        private set
    lateinit var fileCurrentlyBeingAssembled: MIPSprogram
        private set
    lateinit var textAddress: UserKernelAddressSpace
        private set

    private var externAddress = 0
    private var autoAlign = false
    private var dataDirective = Directives.WORD
    private lateinit var globalDeclarations: TokenList
    private lateinit var dataAddress: UserKernelAddressSpace
    private val fileReferences = DataSegmentForwardReferences()

    @Throws(ProcessingException::class)
    fun process(programs: List<MIPSprogram>, extendedAssemblerEnabled: Boolean): ErrorList {
        require(programs.isNotEmpty()) { "At least one source file is required" }
        require(programs.all { context.sharesSession(it.assemblyContext()) }) {
            "All source files must belong to the assembler's session"
        }
        checkCancellation.run()
        context.beginAssembly(programs.first())
        val memory = context.memory()
        textAddress = UserKernelAddressSpace(memory.textBaseAddress, memory.kernelTextBaseAddress)
        dataAddress = UserKernelAddressSpace(memory.dataBaseAddress, memory.kernelDataBaseAddress)
        externAddress = memory.externBaseAddress
        val unresolved = DataSegmentForwardReferences()
        context.symbols().clear()
        context.clearMemory()
        errors = context.newErrorList()

        for (program in programs) {
            checkCancellation.run()
            if (errors.errorLimitExceeded()) break
            fileCurrentlyBeingAssembled = program
            globalDeclarations = TokenList()
            // Each file starts in user text and retains the addresses accumulated so far.
            inDataSegment = false
            textAddress.kernel = false
            inMacroSegment = false
            autoAlign = true
            dataDirective = Directives.WORD
            val symbols = program.localSymbolTable!!
            symbols.clear()
            fileReferences.clear()
            val lines = program.sourceLineList!!
            val tokenLists = program.tokenList!!
            val parsed = program.createParsedList()
            val macroPool = program.createMacroPool()
            for (index in tokenLists.indices) {
                checkCancellation.run()
                if (errors.errorLimitExceeded()) break
                val line = lines[index]
                val tokens = tokenLists[index]
                for (tokenIndex in 0 until tokens.size()) {
                    tokens[tokenIndex].setOriginal(line.getMIPSprogram(), line.lineNumber)
                }
                parser.parseLine(tokens, line, extendedAssemblerEnabled)?.let(parsed::addAll)
            }
            if (inMacroSegment) {
                errors.add(ErrorMessage(program, macroPool.current!!.fromLine, 0, "Macro started but not ended (no .end_macro directive)"))
            }
            transferGlobals()
            fileReferences.resolve(symbols)
            unresolved.add(fileReferences)
            fileReferences.clear()
        }
        unresolved.resolve(context.symbols())
        unresolved.reportMissing()
        if (errors.errorsOccurred()) throw ProcessingException(errors)
        return errors
    }

    fun detectLabels(tokens: TokenList, current: Macro?) {
        if (tokens.beginsWithLabel()) current!!.addLabel(tokens[0].value)
    }

    fun compactTranslationCanBeApplied(statement: ProgramStatement): Boolean =
        PseudoInstructionExpander.compact(context, statement)

    fun stripLabels(tokens: TokenList, physicalLine: SourceLine, caller: Origin?, renamedLabels: Set<String>) {
        if (!tokens.beginsWithLabel()) return
        val token = tokens[0]
        tokens.remove(0)
        tokens.remove(0) // The colon shifted to the first position.
        if (token.type == TokenTypes.OPERATOR) token.type = TokenTypes.IDENTIFIER
        fileCurrentlyBeingAssembled.localSymbolTable!!.addSymbol(
            token, if (inDataSegment) dataAddress.get() else textAddress.get(),
            if (inDataSegment) Kind.DATA else Kind.TEXT, Parser.origin(physicalLine, caller),
            token.value in renamedLabels, errors,
        )
    }

    fun executeDirective(tokens: TokenList, physicalLine: SourceLine, caller: Origin?, renamedLabels: Set<String>) {
        val token = tokens[0]
        when (val directive = Directives.matchDirective(token.value)) {
            null -> errors.add(ErrorMessage(token, "\"$token\" directive is invalid or not implemented in MARS"))
            Directives.EQV -> Unit // Already processed by the tokenizer.
            Directives.MACRO -> beginMacro(tokens)
            Directives.END_MACRO -> endMacro(tokens)
            else -> if (!inMacroSegment) applyDirective(tokens, directive, physicalLine, caller, renamedLabels)
        }
    }

    private fun beginMacro(tokens: TokenList) {
        val token = tokens[0]
        if (tokens.size() < 2) {
            errors.add(ErrorMessage(token, "\"$token\" directive requires at least one argument."))
            return
        }
        val name = tokens[1]
        if (name.type != TokenTypes.IDENTIFIER) {
            errors.add(ErrorMessage(name, "Invalid Macro name \"$name\""))
            return
        }
        if (inMacroSegment) {
            errors.add(ErrorMessage(token, "Nested macros are not allowed"))
            return
        }
        inMacroSegment = true
        val pool = fileCurrentlyBeingAssembled.localMacroPool!!
        pool.beginMacro(name)
        for (index in 2 until tokens.size()) {
            val argument = tokens[index]
            if (argument.type == TokenTypes.RIGHT_PAREN || argument.type == TokenTypes.LEFT_PAREN) continue
            if (!Macro.tokenIsMacroParameter(argument.value, true)) {
                errors.add(ErrorMessage(argument, "Invalid macro argument '$argument'"))
                return
            }
            pool.current!!.addArg(argument.value)
        }
    }

    private fun endMacro(tokens: TokenList) {
        val token = tokens[0]
        if (tokens.size() > 1) {
            errors.add(ErrorMessage(token, "invalid text after .END_MACRO"))
            return
        }
        if (!inMacroSegment) {
            errors.add(ErrorMessage(token, ".END_MACRO without .MACRO"))
            return
        }
        inMacroSegment = false
        fileCurrentlyBeingAssembled.localMacroPool!!.commitMacro(token)
    }

    private fun applyDirective(
        tokens: TokenList, directive: Directives, physicalLine: SourceLine, caller: Origin?, renamedLabels: Set<String>,
    ) {
        val token = tokens[0]
        when (directive) {
            Directives.DATA, Directives.KDATA, Directives.TEXT, Directives.KTEXT -> {
                inDataSegment = directive == Directives.DATA || directive == Directives.KDATA
                val address = if (inDataSegment) {
                    autoAlign = true
                    dataAddress
                } else textAddress
                address.kernel = directive == Directives.KDATA || directive == Directives.KTEXT
                if (tokens.size() > 1 && TokenTypes.isIntegerTokenType(tokens[1].type)) {
                    address.set(Binary.stringToInt(tokens[1].value))
                }
            }
            Directives.WORD, Directives.HALF, Directives.BYTE, Directives.FLOAT, Directives.DOUBLE -> {
                dataDirective = directive
                if (passesDataSegmentCheck(token) && tokens.size() > 1) storeNumeric(tokens, directive)
            }
            Directives.ASCII, Directives.ASCIIZ -> {
                dataDirective = directive
                if (passesDataSegmentCheck(token)) storeStrings(tokens, directive)
            }
            Directives.ALIGN, Directives.SPACE -> applyDataLayout(tokens, directive)
            Directives.EXTERN -> declareExternal(tokens, physicalLine, caller, renamedLabels)
            Directives.SET -> errors.add(ErrorMessage(ErrorMessage.WARNING, token, "MARS currently ignores the .set directive."))
            Directives.GLOBL -> declareGlobals(tokens)
            else -> errors.add(ErrorMessage(token, "\"$token\" directive recognized but not yet implemented."))
        }
    }

    private fun applyDataLayout(tokens: TokenList, directive: Directives) {
        val token = tokens[0]
        if (!passesDataSegmentCheck(token)) return
        if (tokens.size() != 2) {
            errors.add(ErrorMessage(token, "\"$token\" requires one operand"))
            return
        }
        val value = nonNegativeInteger(tokens[1])
        if (value == null) {
            errors.add(ErrorMessage(token, "\"$token\" requires a non-negative integer"))
            return
        }
        when {
            directive == Directives.SPACE -> dataAddress.increment(value)
            value == 0 -> autoAlign = false
            else -> dataAddress.set(alignToBoundary(dataAddress.get(), 2.0.pow(value).toInt()))
        }
    }

    private fun nonNegativeInteger(token: Token): Int? =
        if (TokenTypes.isIntegerTokenType(token.type)) Binary.stringToInt(token.value).takeIf { it >= 0 } else null

    private fun declareExternal(tokens: TokenList, physicalLine: SourceLine, caller: Origin?, renamedLabels: Set<String>) {
        val token = tokens[0]
        if (tokens.size() != 3) {
            errors.add(ErrorMessage(token, "\"$token\" directive requires two operands (label and size)."))
            return
        }
        val size = nonNegativeInteger(tokens[2])
        if (size == null) {
            errors.add(ErrorMessage(token, "\"$token\" requires a non-negative integer size"))
            return
        }
        val name = tokens[1]
        val existing = context.symbols().getSymbol(name.value)
        if (existing == null || existing.address == SymbolTable.NOT_FOUND) {
            context.symbols().addSymbol(
                name, externAddress, Kind.EXTERNAL, Parser.origin(physicalLine, caller), name.value in renamedLabels, errors,
            )
            externAddress += size
        } else {
            symbolUses.record(name, existing)
        }
    }

    private fun declareGlobals(tokens: TokenList) {
        val token = tokens[0]
        if (tokens.size() < 2) {
            errors.add(ErrorMessage(token, "\"$token\" directive requires at least one argument."))
            return
        }
        for (index in 1 until tokens.size()) {
            val label = tokens[index]
            if (label.type != TokenTypes.IDENTIFIER) {
                errors.add(ErrorMessage(token, "\"$token\" directive argument must be label."))
                return
            }
            globalDeclarations.add(label)
        }
    }

    private fun transferGlobals() {
        val local = fileCurrentlyBeingAssembled.localSymbolTable!!
        val global = context.symbols()
        for (index in 0 until globalDeclarations.size()) {
            checkCancellation.run()
            val label = globalDeclarations[index]
            val symbol = local.getSymbol(label.value)
            when {
                symbol == null -> errors.add(ErrorMessage(label, "\"$label\" declared global label but not defined."))
                global.getAddress(label.value) != SymbolTable.NOT_FOUND ->
                    errors.add(ErrorMessage(label, "\"$label\" already defined as global in a different file."))
                else -> {
                    local.removeSymbol(label)
                    global.addSymbol(label, symbol, errors)
                    symbolUses.record(label, symbol)
                }
            }
        }
    }

    fun executeDirectiveContinuation(tokens: TokenList) {
        when (val directive = dataDirective) {
            Directives.WORD, Directives.HALF, Directives.BYTE, Directives.FLOAT, Directives.DOUBLE ->
                if (!tokens.isEmpty) storeNumeric(tokens, directive)
            Directives.ASCII, Directives.ASCIIZ -> if (passesDataSegmentCheck(tokens[0])) storeStrings(tokens, directive)
            else -> Unit
        }
    }

    fun matchInstruction(token: Token): ArrayList<Instruction>? {
        if (token.type != TokenTypes.OPERATOR) {
            val message = if (token.sourceMIPSprogram!!.localMacroPool!!.matchesAnyMacroName(token.value)) {
                "forward reference or invalid parameters for macro \"$token\""
            } else {
                "\"$token\" is not a recognized operator"
            }
            errors.add(ErrorMessage(token, message))
            return null
        }
        val matches = context.instructions().matchOperator(token.value)
        if (matches == null) errors.add(ErrorMessage(token, "Internal Assembler error: \"$token\" tokenized OPERATOR then not recognized"))
        return matches
    }

    private fun storeNumeric(tokens: TokenList, directive: Directives) {
        if (!passesDataSegmentCheck(tokens[0])) return
        val integer = Directives.isIntegerDirective(directive)
        if (tokens.hasNumericRepetitionShape()) {
            val value = tokens[1]
            val count = tokens[3]
            val validValue = TokenTypes.isIntegerTokenType(value.type) || !integer && TokenTypes.isFloatingTokenType(value.type)
            if (!validValue || !TokenTypes.isIntegerTokenType(count.type)) {
                errors.add(ErrorMessage(value, "malformed expression"))
                return
            }
            val repetitions = Binary.stringToInt(count.value)
            if (repetitions <= 0) {
                errors.add(ErrorMessage(count, "repetition factor must be positive"))
                return
            }
            alignData(DataTypes.getLengthInBytes(directive))
            repeat(repetitions) {
                checkCancellation.run()
                if (integer) storeInteger(value, directive) else storeRealNumber(value, directive)
            }
            return
        }

        val firstValue = if (tokens[0].type == TokenTypes.DIRECTIVE) 1 else 0
        for (index in firstValue until tokens.size()) {
            checkCancellation.run()
            val token = tokens[index]
            if (integer) storeInteger(token, directive) else storeRealNumber(token, directive)
        }
    }

    private fun storeInteger(token: Token, directive: Directives) {
        val length = DataTypes.getLengthInBytes(directive)
        when {
            TokenTypes.isIntegerTokenType(token.type) -> {
                val fullValue = Binary.stringToInt(token.value)
                val value = when (directive) {
                    Directives.BYTE -> fullValue and 0xff
                    Directives.HALF -> fullValue and 0xffff
                    else -> fullValue
                }
                if (DataTypes.outOfRange(directive, fullValue)) {
                    errors.add(ErrorMessage(
                        ErrorMessage.WARNING, token, "\"$token\" is out-of-range for a signed value and possibly truncated",
                    ))
                }
                writeToDataSegment(value, length, token)
            }
            token.type == TokenTypes.IDENTIFIER -> {
                val symbol = fileCurrentlyBeingAssembled.localSymbolTable!!.getSymbolLocalOrGlobal(token.value)
                if (symbol == null || symbol.address == SymbolTable.NOT_FOUND) {
                    val address = writeToDataSegment(0, length, token)
                    fileReferences.add(address, length, token)
                } else {
                    symbolUses.record(token, symbol)
                    writeToDataSegment(symbol.address, length, token)
                }
            }
            else -> errors.add(ErrorMessage(token, "\"$token\" is not a valid integer constant or label"))
        }
    }

    private fun storeRealNumber(token: Token, directive: Directives) {
        val value = if (TokenTypes.isIntegerTokenType(token.type) || TokenTypes.isFloatingTokenType(token.type)) {
            token.value.toDoubleOrNull()
        } else null
        when {
            value == null -> errors.add(ErrorMessage(token, "\"$token\" is not a valid floating point constant"))
            DataTypes.outOfRange(directive, value) -> errors.add(ErrorMessage(token, "\"$token\" is an out-of-range value"))
            directive == Directives.FLOAT -> writeToDataSegment(value.toFloat().toBits(), DataTypes.FLOAT_SIZE, token)
            directive == Directives.DOUBLE -> writeDoubleToDataSegment(value, token)
        }
    }

    private fun storeStrings(tokens: TokenList, directive: Directives) {
        val firstValue = if (tokens[0].type == TokenTypes.DIRECTIVE) 1 else 0
        for (index in firstValue until tokens.size()) {
            checkCancellation.run()
            val token = tokens[index]
            if (token.type != TokenTypes.QUOTED_STRING) {
                errors.add(ErrorMessage(token, "\"$token\" is not a valid character string"))
                continue
            }
            if (!token.hasClosingDoubleQuote()) {
                errors.add(ErrorMessage(token, "Unterminated string literal"))
                continue
            }
            val quote = token.value
            var position = 1
            while (position < quote.lastIndex) {
                var character = quote[position++]
                if (character == '\\') {
                    character = when (val escaped = quote[position++]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        'b' -> '\b'
                        'f' -> '\u000c'
                        '0' -> '\u0000'
                        else -> escaped
                    }
                }
                storeCharacter(character.code, token)
            }
            if (directive == Directives.ASCIIZ) storeCharacter(0, token)
        }
    }

    private fun storeCharacter(value: Int, token: Token) {
        try {
            context.memory().set(dataAddress.get(), value, DataTypes.CHAR_SIZE)
        } catch (_: AddressErrorException) {
            reportInvalidDataAddress(token)
        }
        dataAddress.increment(DataTypes.CHAR_SIZE)
    }

    private fun passesDataSegmentCheck(token: Token): Boolean {
        if (inDataSegment) return true
        errors.add(ErrorMessage(token, "\"$token\" directive cannot appear in text segment"))
        return false
    }

    /** Returns the address to use when backpatching a forward reference. */
    private fun writeToDataSegment(value: Int, length: Int, token: Token): Int {
        alignData(length)
        val address = dataAddress.get()
        try {
            context.memory().set(address, value, length)
        } catch (_: AddressErrorException) {
            reportInvalidDataAddress(token)
            return address
        }
        dataAddress.increment(length)
        return address
    }

    private fun writeDoubleToDataSegment(value: Double, token: Token) {
        alignData(DataTypes.DOUBLE_SIZE)
        try {
            context.memory().setDouble(dataAddress.get(), value)
        } catch (_: AddressErrorException) {
            reportInvalidDataAddress(token)
            return
        }
        dataAddress.increment(DataTypes.DOUBLE_SIZE)
    }

    private fun reportInvalidDataAddress(token: Token) {
        errors.add(ErrorMessage(token, "\"${dataAddress.get()}\" is not a valid data segment address"))
    }

    private fun alignData(length: Int) {
        if (autoAlign) dataAddress.set(alignToBoundary(dataAddress.get(), length))
    }

    private fun alignToBoundary(address: Int, boundary: Int): Int {
        val remainder = address % boundary
        if (remainder == 0) return address
        val aligned = address + boundary - remainder
        fileCurrentlyBeingAssembled.localSymbolTable!!.fixSymbolTableAddress(address, aligned)
        return aligned
    }

    class UserKernelAddressSpace(userBase: Int, kernelBase: Int) {
        private val addresses = intArrayOf(userBase, kernelBase)
        var kernel = false

        private val index: Int get() = if (kernel) 1 else 0

        fun get(): Int = addresses[index]

        fun set(value: Int) {
            addresses[index] = value
        }

        fun increment(increment: Int) {
            addresses[index] += increment
        }
    }

    private data class DataSegmentForwardReference(val patchAddress: Int, val length: Int, val token: Token)

    private inner class DataSegmentForwardReferences {
        private val entries = ArrayList<DataSegmentForwardReference>()

        fun add(address: Int, length: Int, token: Token) {
            entries.add(DataSegmentForwardReference(address, length, token))
        }

        fun add(other: DataSegmentForwardReferences) {
            entries.addAll(other.entries)
        }

        fun clear() {
            entries.clear()
        }

        fun resolve(symbols: SymbolTable) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                checkCancellation.run()
                val entry = iterator.next()
                val symbol = symbols.getSymbolLocalOrGlobal(entry.token.value)
                if (symbol == null || symbol.address == SymbolTable.NOT_FOUND) continue
                try {
                    context.memory().set(entry.patchAddress, symbol.address, entry.length)
                } catch (_: AddressErrorException) {
                    // The initial data write already reported an invalid patch address.
                }
                symbolUses.record(entry.token, symbol)
                iterator.remove()
            }
        }

        fun reportMissing() {
            for (entry in entries) {
                checkCancellation.run()
                errors.add(ErrorMessage(entry.token, "Symbol \"${entry.token}\" not found in symbol table."))
            }
        }
    }
}
