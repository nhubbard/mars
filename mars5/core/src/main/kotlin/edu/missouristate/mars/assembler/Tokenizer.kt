package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.api.AssemblySymbolMap.EquivalenceOccurrence
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.mips.instructions.InstructionSet
import java.io.File
import java.nio.file.Path
import java.util.Set.copyOf as copySet
import java.util.function.Function
import kotlin.jvm.JvmRecord

/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

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

/** Scans assembly lines and tracks their written positions through include and equivalence expansion. */
open class Tokenizer @JvmOverloads constructor(
    program: MIPSprogram? = null,
    private val instructions: InstructionSet? = null,
) {
    private var sourceProgram = program
    private var errors = program?.newErrorList() ?: ErrorList()
    private val equivalents = HashMap<String, EquivalenceReplacement>()
    private val equivalenceOccurrences = HashSet<EquivalenceOccurrence>()

    @Throws(ProcessingException::class)
    open fun tokenize(program: MIPSprogram): ArrayList<TokenList> = tokenize(program, {})

    @Throws(ProcessingException::class)
    open fun tokenize(program: MIPSprogram, checkCancellation: Runnable): ArrayList<TokenList> {
        sourceProgram = program
        equivalents.clear()
        equivalenceOccurrences.clear()
        val source = processIncludes(program, checkCancellation)
        program.sourceLineList = source
        val result = ArrayList<TokenList>(source.size)
        for (index in source.indices) {
            val line = source[index]
            val tokens = tokenizeLine(program, index + 1, line.source, true, checkCancellation)
            result.add(tokens)
            if (line.source.isNotEmpty() && line.source != tokens.processedLine) {
                source[index] = SourceLine(tokens.processedLine, line.getMIPSprogram(), line.lineNumber)
            }
        }
        if (errors.errorsOccurred()) throw ProcessingException(errors)
        return result
    }

    private class IncludeFrame(val program: MIPSprogram) {
        val lines = program.sourceList.listIterator()
    }

    // Expand depth first and retain each line's original file and line number.
    private fun processIncludes(root: MIPSprogram, checkCancellation: Runnable): ArrayList<SourceLine> {
        val pending = ArrayDeque<IncludeFrame>()
        pending.addLast(IncludeFrame(root))
        val includedPaths = HashSet<Path>()
        val result = ArrayList<SourceLine>(root.sourceList.size)
        while (pending.isNotEmpty()) {
            val frame = pending.last()
            val program = frame.program
            val lines = frame.lines
            if (!lines.hasNext()) {
                pending.removeLast()
                continue
            }
            checkCancellation.run()
            val line = lines.next()
            val lineNumber = lines.nextIndex()
            val candidates = scanPossibleInclude(program, lineNumber, line, checkCancellation)
            val includeIndex = firstIncludePathIndex(candidates)
            val include = if (includeIndex >= 0) candidates!![includeIndex] else null
            val path = include?.let(::quotedIncludePath)
            if (include == null || path == null) {
                if (include != null) {
                    errors.add(ErrorMessage(program, include.sourceLine, include.startPos, "Unterminated .include path"))
                }
                result.add(SourceLine(line, program, lineNumber))
                continue
            }
            // The assembly pass only sees the included lines.
            reportLexicalErrors(program, lineNumber, line, candidates!!)
            val filename = resolveIncludePath(program.filename, path)
            val included = program.createRelatedProgram()
            try {
                included.readIncludedSource(filename, checkCancellation)
            } catch (_: ProcessingException) {
                errors.add(ErrorMessage(program, include.sourceLine, include.startPos, "Error reading include file $filename"))
                throw ProcessingException(errors)
            }
            if (includedPaths.isEmpty()) includedPaths.add(root.includeIdentity)
            if (!includedPaths.add(included.includeIdentity)) {
                errors.add(ErrorMessage(program, include.sourceLine, include.startPos, "Recursive include of file ${included.filename}"))
                throw ProcessingException(errors)
            }
            pending.addLast(IncludeFrame(included))
        }
        return result
    }

    /** Finds the first literal include path before any equivalence expansion. */
    open fun findIncludePath(sourceLine: String): String? = findInclude(sourceLine)?.path

    /** Finds the include and the zero-based offsets of its directive and opening quote. */
    open fun findInclude(sourceLine: String): IncludePath? = inspectInclude(sourceLine)?.takeIf { it.path != null }

    /** The path is absent when the opening quote is unfinished. */
    open fun inspectInclude(sourceLine: String): IncludePath? {
        val candidates = scanPossibleInclude(sourceProgram, 1, sourceLine, {}) ?: return null
        val index = firstIncludePathIndex(candidates)
        if (index < 0) return null
        val token = candidates[index]
        return IncludePath(quotedIncludePath(token), token.startPos - 1, candidates[index - 1].startPos - 1)
    }

    @JvmRecord
    data class IncludePath(val path: String?, val offset: Int, val directiveOffset: Int)

    private fun scanPossibleInclude(
        program: MIPSprogram?, lineNumber: Int, line: String, checkCancellation: Runnable,
    ): TokenList? = if ('.' in line) scanLine(program, lineNumber, line, checkCancellation) else null

    @Throws(ProcessingException::class)
    open fun tokenizeExampleInstruction(example: String): TokenList {
        val result = tokenizeLine(sourceProgram, 0, example, false)
        if (errors.errorsOccurred()) throw ProcessingException(errors)
        return result
    }

    open fun tokenizeLine(lineNum: Int, line: String): TokenList = tokenizeLine(sourceProgram, lineNum, line, true)

    open fun tokenizeLine(lineNum: Int, line: String, errors: ErrorList): TokenList =
        tokenizeLine(lineNum, line, errors, true)

    open fun tokenizeLine(lineNum: Int, line: String, errors: ErrorList, substituteEquivalences: Boolean): TokenList =
        tokenizeLine(lineNum, line, errors, substituteEquivalences, {}, null)

    private fun tokenizeLine(
        lineNum: Int, line: String, callerErrors: ErrorList, substituteEquivalences: Boolean,
        checkCancellation: Runnable, expansion: Macro.ExpandedLine?,
    ): TokenList {
        val savedErrors = errors
        errors = callerErrors
        try {
            return tokenizeLine(sourceProgram, lineNum, line, substituteEquivalences, checkCancellation, expansion)
        } finally {
            errors = savedErrors
        }
    }

    fun tokenizeMacroLine(
        lineNum: Int, expansion: Macro.ExpandedLine, callerErrors: ErrorList, checkCancellation: Runnable,
    ): TokenList {
        val tokens = tokenizeLine(lineNum, expansion.text, callerErrors, true, checkCancellation, expansion)
        for (index in 0 until tokens.size()) {
            val token = tokens[index]
            val origin = expansion.tokenOriginAt(token.originalStartPos) ?: continue
            token.setDiagnosticOrigin(origin.diagnostic)
            token.setExpansionSource(origin.written)
            if (origin.diagnostic !== origin.written) token.setArgumentOrigin(origin.diagnostic)
            if (token.equivalenceSource() == null) token.setEquivalenceSource(origin.diagnostic.equivalenceSource())
        }
        return tokens
    }

    class MappedLine private constructor(private val expanded: ExpandedLine, private val edits: List<SourceEdit>) {
        fun tokens(): TokenList = expanded.tokens

        fun expanded(): ExpandedLine = expanded

        /** Zero-based written offsets at which an equivalence was substituted. */
        fun substitutionOffsets(): Set<Int> = copySet(edits.map { it.originalStart })

        /** Written offset of the directive introducing the definition, or -1. */
        fun equivalenceDirectiveOffset(): Int =
            if (expanded.definition == null) -1 else tokens()[equivalenceDirectiveIndex(tokens())].originalStartPos - 1

        /** Maps a zero-based token offset to the original character or its alias. */
        fun originalOffset(offset: Int): Int = originalOffset(edits, offset)

        companion object {
            internal fun tokenize(
                line: String, errors: ErrorList, replacements: Function<String, String?>, checkCancellation: Runnable,
            ): MappedLine {
                val edits = ArrayList<SourceEdit>()
                return MappedLine(expandLine(line, errors, replacements, checkCancellation, edits), edits)
            }
        }
    }

    private data class SourceEdit(
        val start: Int, val removedLength: Int, val originalStart: Int, val replacement: EquivalenceReplacement,
    ) {
        val insertedLength: Int get() = replacement.text.length
    }

    private data class ReplacementSpelling(val end: Int, val source: SourceToken)

    private data class EquivalenceReplacement(
        val text: String, val source: SourceToken?, val definition: SourceToken?, val spellings: Map<Int, ReplacementSpelling>,
    ) {
        fun spelling(start: Int, end: Int): SourceToken? {
            if (source != null) return SourceToken(source.location, source.column + start)
            return spellings[start]?.takeIf { it.end == end }?.source
        }
    }

    fun equivalenceOccurrences(): Set<EquivalenceOccurrence> = equivalenceOccurrences

    @JvmRecord
    data class EquivalenceDefinition(val name: String, val replacement: String)

    @JvmRecord
    data class ExpandedLine(val tokens: TokenList, val definition: EquivalenceDefinition?)

    private fun expandDetachedLine(
        line: String, errors: ErrorList, replacements: Function<String, String?>,
        checkCancellation: Runnable, edits: MutableList<SourceEdit>?,
    ): TokenList {
        this.errors = errors
        val tokens = tokenizeLine(null, 1, line, false, checkCancellation)
        return processEqv(null, 1, line, tokens, { name ->
            replacements.apply(name)?.let { EquivalenceReplacement(it, null, null, emptyMap()) }
        }, checkCancellation, edits, null)
    }

    open fun tokenizeLine(program: MIPSprogram?, lineNum: Int, line: String, substituteEquivalences: Boolean): TokenList =
        tokenizeLine(program, lineNum, line, substituteEquivalences, {})

    private fun tokenizeLine(
        program: MIPSprogram?, lineNum: Int, line: String, substituteEquivalences: Boolean,
        checkCancellation: Runnable, expansion: Macro.ExpandedLine? = null,
    ): TokenList {
        val tokens = scanLine(program, lineNum, line, checkCancellation)
        reportLexicalErrors(program, lineNum, line, tokens, expansion = expansion)
        return if (substituteEquivalences && line.isNotEmpty()) {
            processEqv(program, lineNum, line, tokens, equivalents::get, checkCancellation, null, expansion)
        } else tokens
    }

    private fun scanLine(program: MIPSprogram?, lineNum: Int, line: String, checkCancellation: Runnable): TokenList {
        checkCancellation.run()
        val tokens = TokenList()
        if (line.isEmpty()) return tokens
        if (sourceProgram?.assemblyContext()?.debug() == true) println("source line --->$line<---")
        var position = 0
        var start = 0
        var quoted = false
        var nextCheck = 256

        fun emit(end: Int) {
            if (end > start) addToken(line, start, end, program, lineNum, tokens)
            start = end
        }

        while (position < line.length) {
            if (position >= nextCheck) {
                checkCancellation.run()
                nextCheck = position + 256
            }
            val char = line[position]
            if (quoted) {
                if (char == '"' && line[position - 1] != '\\') {
                    emit(position + 1)
                    quoted = false
                }
                position++
                continue
            }

            when (char) {
                '#' -> {
                    emit(position)
                    emit(line.length)
                    return tokens
                }
                ' ', '\t', ',' -> {
                    emit(position)
                    start = position + 1
                }
                '+', '-' -> {
                    val followedByDigit = position + 1 < line.length && line[position + 1].isDigit()
                    val signedExponent = position > start && followedByDigit && line[position - 1] in "eE"
                    if (!signedExponent) {
                        emit(position)
                        val unary = followedByDigit && (tokens.isEmpty || tokens[tokens.size() - 1].type != TokenTypes.IDENTIFIER)
                        if (!unary) emit(position + 1)
                    }
                }
                ':', '(', ')' -> {
                    emit(position)
                    emit(position + 1)
                }
                '"' -> {
                    emit(position)
                    quoted = true
                }
                '\'' -> {
                    emit(position)
                    val remaining = line.length - position - 1
                    // Preserve the scanner's recovery for incomplete and empty character literals.
                    if (remaining < 2) {
                        position++
                        continue
                    }
                    if (line[position + 1] == '\'') {
                        position += 2
                        continue
                    }
                    val end = when {
                        remaining == 2 || line[position + 2] == '\'' && line[position + 1] != '\\' -> position + 3
                        remaining == 3 || line[position + 3] == '\'' || remaining == 4 -> position + 4
                        line[position + 4] == '\'' -> position + 5
                        else -> position + 6
                    }
                    emit(end)
                    position = end
                    continue
                }
            }
            position++
        }
        emit(line.length)
        return tokens
    }

    private fun addToken(line: String, start: Int, end: Int, program: MIPSprogram?, lineNum: Int, tokens: TokenList) {
        val written = line.substring(start, end)
        val character = if (written.first() == '\'') decodeCharacterLiteral(written) else -1
        val value = if (character >= 0) character.toString() else written
        val type = TokenTypes.matchTokenType(value, instructions ?: InstructionDefaults.get())
        tokens.add(Token(type, value, program, lineNum, start + 1, end - start))
    }

    // Re-scan replacements as source text so they can introduce punctuation and directives.
    private fun processEqv(
        originalProgram: MIPSprogram?, lineNum: Int, originalLine: String, originalTokens: TokenList,
        replacements: (String) -> EquivalenceReplacement?, checkCancellation: Runnable,
        sourceEdits: MutableList<SourceEdit>?, expansion: Macro.ExpandedLine?,
    ): TokenList {
        var program = originalProgram
        var line = originalLine
        var tokens = originalTokens
        var use = nextEquivalenceUse(program, lineNum, line, tokens, replacements, emptyList(), expansion)
            ?: return tokens
        val edits = sourceEdits ?: ArrayList()
        var originalColumn = 0
        val path = ArrayDeque<EquivalenceExpansion>()
        val active = HashSet<String>()
        while (true) {
            val token = use.token
            val symbol = token.value
            val start = token.startPos - 1
            // Everything after a substitution stays fixed. Its length tracks the expansion's end.
            val remaining = line.length - start
            while (path.isNotEmpty() && remaining <= path.last().suffixLength) {
                active.remove(path.removeLast().symbol)
            }
            if (path.isEmpty()) originalColumn = token.startPos + originalLine.length - line.length
            if (!active.add(symbol)) {
                val chain = path.joinToString(" -> ") { it.symbol }
                addLineError(program, lineNum, originalColumn, "Circular .eqv expansion $chain -> $symbol", expansion)
                tokens.markEquivalenceExpansionIncomplete()
                tokens.processedLine = originalLine
                break
            }
            val end = token.endPos - 1
            path.addLast(EquivalenceExpansion(symbol, line.length - end))
            val replacement = use.replacement
            val definition = replacement.definition
            if (definition != null) {
                val location = physicalLocation(program, lineNum)
                if (location != null) {
                    val site = equivalenceSite(token, edits, location, expansion)
                    val spelling = spellingSource(edits, start, end, location, expansion)
                    equivalenceOccurrences.add(EquivalenceOccurrence(site, spelling, definition, symbol, false))
                }
            }
            edits.add(SourceEdit(start, end - start, originalColumn - 1, replacement))
            line = line.replaceRange(start, end, replacement.text)
            program = sourceProgram
            tokens = scanLine(program, lineNum, line, checkCancellation)
            use = nextEquivalenceUse(program, lineNum, line, tokens, replacements, edits, expansion) ?: break
        }
        for (index in 0 until tokens.size()) {
            checkCancellation.run()
            val token = tokens[index]
            token.setOriginalStartPos(originalColumn(token, edits))
            token.setEquivalenceSource(spellingSource(edits, token.startPos - 1, token.endPos - 1, null, null))
        }
        reportLexicalErrors(originalProgram, lineNum, originalLine, tokens, originalTokens, expansion)
        return tokens
    }

    private data class EquivalenceExpansion(val symbol: String, val suffixLength: Int)

    private data class EquivalenceUse(val token: Token, val replacement: EquivalenceReplacement)

    private fun nextEquivalenceUse(
        program: MIPSprogram?, lineNum: Int, line: String, tokens: TokenList,
        replacements: (String) -> EquivalenceReplacement?, edits: List<SourceEdit>, expansion: Macro.ExpandedLine?,
    ): EquivalenceUse? {
        tokens.processedLine = line
        if (recordEquivalence(program, lineNum, line, tokens, edits, expansion)) return null
        for (index in 0 until tokens.size()) {
            val token = tokens[index]
            if (token.type == TokenTypes.IDENTIFIER) {
                val replacement = replacements(token.value) ?: continue
                return EquivalenceUse(token, replacement)
            }
        }
        return null
    }

    private fun recordEquivalence(
        program: MIPSprogram?, lineNum: Int, line: String, tokens: TokenList, edits: List<SourceEdit>,
        expansion: Macro.ExpandedLine?,
    ): Boolean {
        if (tokens.size() < 3) return false
        val directive = equivalenceDirectiveIndex(tokens)
        val directiveToken = tokens[directive]
        if (directiveToken.type != TokenTypes.DIRECTIVE || Directives.matchDirective(directiveToken.value) != Directives.EQV) {
            return false
        }
        val last = tokens.size() - if (tokens[tokens.size() - 1].type == TokenTypes.COMMENT) 2 else 1
        if (last < directive + 2) {
            addLineError(program, lineNum, originalColumn(directiveToken, edits), "Too few operands for .eqv directive", expansion)
            return true
        }
        val name = tokens[directive + 1]
        if (name.type != TokenTypes.IDENTIFIER) {
            addLineError(program, lineNum, originalColumn(directiveToken, edits), "Malformed .eqv directive", expansion)
            return true
        }
        val symbol = name.value
        for (index in directive + 2..last) {
            if (tokens[index].value == symbol) {
                addLineError(
                    program, lineNum, originalColumn(directiveToken, edits),
                    "Cannot substitute $symbol for itself in .eqv directive", expansion,
                )
                return true
            }
        }
        val start = tokens[directive + 2].startPos - 1
        val end = tokens[last].endPos - 1
        val expression = line.substring(start, end)
        val location = physicalLocation(program, lineNum)
        val site = location?.let { equivalenceSite(name, edits, it, expansion) }
        val source = spellingSource(edits, start, end, location.takeIf { expansion == null }, null)
        val spellings = if (source == null && location != null) {
            // Macro parameters can supply distinct token spellings in the same replacement.
            buildMap {
                for (index in directive + 2..last) {
                    val token = tokens[index]
                    val tokenStart = token.startPos - 1
                    val tokenEnd = token.endPos - 1
                    val spelling = spellingSource(edits, tokenStart, tokenEnd, location, expansion) ?: continue
                    put(tokenStart - start, ReplacementSpelling(tokenEnd - start, spelling))
                }
            }
        } else emptyMap()
        val replacement = EquivalenceReplacement(expression, source, site, spellings)
        val previous = equivalents.putIfAbsent(symbol, replacement)
        if (previous != null && previous.text != expression) {
            addLineError(program, lineNum, originalColumn(name, edits), "\"$symbol\" is already defined", expansion)
        } else if (site != null) {
            val spelling = spellingSource(edits, name.startPos - 1, name.endPos - 1, location, expansion)
            equivalenceOccurrences.add(EquivalenceOccurrence(site, spelling, if (previous == null) site else previous.definition, symbol, true))
        }
        return true
    }

    open fun getErrors(): ErrorList = errors

    private fun reportLexicalErrors(
        program: MIPSprogram?, line: Int, source: String, tokens: TokenList, originalTokens: TokenList? = null,
        expansion: Macro.ExpandedLine? = null,
    ) {
        var originalIndex = 0
        for (index in 0 until tokens.size()) {
            val token = tokens[index]
            if (token.type != TokenTypes.ERROR) continue
            val column = token.originalStartPos
            if (originalTokens != null) {
                // Mapped offsets stay in source order. Skip errors already reported on the written line.
                while (originalIndex < originalTokens.size() && originalTokens[originalIndex].startPos < column) {
                    originalIndex++
                }
                if (originalIndex < originalTokens.size()) {
                    val original = originalTokens[originalIndex]
                    if (original.startPos == column && original.type == TokenTypes.ERROR && original.value == token.value) continue
                }
            }
            addLineError(program, line, column, "$source\nInvalid language element: ${token.value}", expansion)
            if (errors.errorLimitExceeded()) return
        }
    }

    private fun addLineError(program: MIPSprogram?, line: Int, column: Int, message: String, expansion: Macro.ExpandedLine?) {
        val origin = expansion?.originAt(column)
        errors.add(if (origin == null) ErrorMessage(program, line, column, message) else ErrorMessage(origin, message))
    }

    companion object {
        private const val ESCAPED_CHARACTERS = "'\"\\ntbrf0"
        private val escapedValues = charArrayOf('\'', '"', '\\', '\n', '\t', '\b', '\r', '\u000c', '\u0000')

        /** Resolves a literal include against its source using the assembler's filesystem semantics. */
        @JvmStatic
        fun resolveIncludePath(source: String, path: String): String {
            if (File(path).isAbsolute) return path
            // Windows resolves a single leading separator against this parent.
            return File(File(source).absoluteFile.parentFile, path).path
        }

        private fun firstIncludePathIndex(tokens: TokenList?): Int {
            if (tokens == null) return -1
            for (index in 1 until tokens.size()) {
                if (tokens[index - 1].value.equals(Directives.INCLUDE.name, ignoreCase = true) &&
                    tokens[index].type == TokenTypes.QUOTED_STRING
                ) return index
            }
            return -1
        }

        private fun quotedIncludePath(token: Token): String? =
            token.value.takeIf { token.hasClosingDoubleQuote() }?.let { it.substring(1, it.length - 1) }

        /** Reads a valid physical definition of the requested name. */
        @JvmStatic
        fun readEquivalenceReplacement(line: String, name: String): String? {
            val tokenizer = Tokenizer()
            tokenizer.tokenizeLine(1, line)
            return if (tokenizer.errors.errorsOccurred()) null else tokenizer.equivalents[name]?.text
        }

        /** Tokenizes a detached line. The replacement lookup returns null for an undefined name. */
        @JvmStatic
        fun tokenizeWithEquivalences(
            line: String, errors: ErrorList, replacements: Function<String, String?>, checkCancellation: Runnable,
        ): TokenList = Tokenizer().expandDetachedLine(line, errors, replacements, checkCancellation, null)

        /** Tokenizes a detached line and preserves the source positions of expanded tokens. */
        @JvmStatic
        fun tokenizeMappedLine(
            line: String, errors: ErrorList, replacements: Function<String, String?>, checkCancellation: Runnable,
        ): MappedLine = MappedLine.tokenize(line, errors, replacements, checkCancellation)

        @JvmStatic
        fun expandLine(
            line: String, errors: ErrorList, replacements: Function<String, String?>, checkCancellation: Runnable,
        ): ExpandedLine = expandLine(line, errors, replacements, checkCancellation, null)

        private fun expandLine(
            line: String, errors: ErrorList, replacements: Function<String, String?>, checkCancellation: Runnable,
            edits: MutableList<SourceEdit>?,
        ): ExpandedLine {
            val tokenizer = Tokenizer()
            val tokens = tokenizer.expandDetachedLine(line, errors, replacements, checkCancellation, edits)
            val definition = if (errors.errorsOccurred()) null else tokenizer.equivalents.entries.firstOrNull()
            return ExpandedLine(tokens, definition?.let { EquivalenceDefinition(it.key, it.value.text) })
        }

        private fun originalOffset(edits: List<SourceEdit>, offset: Int): Int {
            var original = offset
            for (index in edits.indices.reversed()) {
                val edit = edits[index]
                if (original >= edit.start + edit.insertedLength) {
                    original += edit.removedLength - edit.insertedLength
                } else if (original >= edit.start) {
                    original = edit.start
                }
            }
            return original
        }

        private fun originalColumn(token: Token, edits: List<SourceEdit>): Int = originalOffset(edits, token.startPos - 1) + 1

        /** A contiguous range can inherit its spelling from a replacement or the written line. */
        private fun spellingSource(
            edits: List<SourceEdit>, rangeStart: Int, rangeEnd: Int, physicalLine: Location?, expansion: Macro.ExpandedLine?,
        ): SourceToken? {
            var start = rangeStart
            var end = rangeEnd
            for (index in edits.indices.reversed()) {
                val edit = edits[index]
                val replacementEnd = edit.start + edit.insertedLength
                if (start >= replacementEnd) {
                    val delta = edit.removedLength - edit.insertedLength
                    start += delta
                    end += delta
                } else if (end > edit.start) {
                    if (start < edit.start || end > replacementEnd) return null
                    return edit.replacement.spelling(start - edit.start, end - edit.start)
                }
            }
            if (expansion != null) {
                val token = expansion.tokenOriginStartingAt(start + 1)?.diagnostic ?: return null
                val value = token.value
                if (value.length != end - start || !expansion.text.startsWith(value, start)) return null
                return token.equivalenceSource() ?: if (value == token.diagnosticOrigin.value) token.diagnosticSite() else null
            }
            return physicalLine?.let { SourceToken(it, start + 1) }
        }

        private fun physicalLocation(program: MIPSprogram?, lineNum: Int): Location? {
            val source = program?.sourceLineList?.getOrNull(lineNum - 1) ?: return null
            return Location(Path.of(source.filename!!), source.lineNumber)
        }

        private fun equivalenceSite(
            token: Token, edits: List<SourceEdit>, location: Location, expansion: Macro.ExpandedLine?,
        ): SourceToken {
            val column = originalColumn(token, edits)
            val origin = expansion?.tokenOriginAt(column)
            return SourceToken(location, origin?.written?.originalStartPos ?: column)
        }

        // The third token can be a directive even without a leading label.
        private fun equivalenceDirectiveIndex(tokens: TokenList): Int = if (tokens[0].type == TokenTypes.DIRECTIVE) 0 else 2

        /** Decodes one MARS character literal, or returns -1 for an invalid spelling. */
        @JvmStatic
        fun decodeCharacterLiteral(literal: CharSequence): Int {
            val length = literal.length
            if (length < 3 || literal.first() != '\'' || literal.last() != '\'') return -1
            val character = literal[1]
            if (character != '\\') return if (length == 3 && character != '\'') character.code else -1
            if (length == 4) return escapedValues.getOrNull(ESCAPED_CHARACTERS.indexOf(literal[2]))?.code ?: -1
            if (length == 6) {
                val value = try {
                    Integer.parseInt(literal, 2, length - 1, 8)
                } catch (_: NumberFormatException) {
                    return -1
                }
                if (value in 0..255) return value
            }
            return -1
        }
    }
}
