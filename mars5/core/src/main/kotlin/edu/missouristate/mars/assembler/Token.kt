package edu.missouristate.mars.assembler

import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap.Location
import java.nio.file.Path

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/** A token and its position before and after include, macro, and equivalence expansion. */
open class Token internal constructor(
    open var type: TokenTypes,
    open val value: String,
    open val sourceMIPSprogram: MIPSprogram?,
    open val sourceLine: Int,
    open val startPos: Int,
    sourceLength: Int,
) {
    constructor(type: TokenTypes, value: String, program: MIPSprogram?, line: Int, start: Int) :
        this(type, value, program, line, start, value.length)

    private var originalColumn = startPos
    private var originalMipsProgram = sourceMIPSprogram
    private var originalLine = sourceLine
    private var diagnosticToken: Token? = null
    private var equivalenceSource: SourceToken? = null
    private var expansionSource: Token? = null
    private var argumentOrigin: Token? = null

    open val endPos = startPos + sourceLength
    open val originalStartPos: Int get() = originalColumn
    open val originalProgram: MIPSprogram? get() = originalMipsProgram
    open val originalSourceLine: Int get() = originalLine

    open fun setOriginal(program: MIPSprogram?, line: Int) {
        originalMipsProgram = program
        originalLine = line
    }

    fun withType(type: TokenTypes): Token =
        Token(type, value, sourceMIPSprogram, sourceLine, startPos, endPos - startPos).also { copy ->
            copy.setOriginal(originalMipsProgram, originalLine)
            copy.originalColumn = originalColumn
            copy.diagnosticToken = diagnosticToken
            copy.expansionSource = expansionSource
            copy.argumentOrigin = argumentOrigin
            copy.equivalenceSource = equivalenceSource
        }

    open fun hasClosingDoubleQuote(): Boolean = value.length > 1 && value.last() == '"'

    override fun toString(): String = value

    fun setOriginalStartPos(column: Int) {
        originalColumn = column
    }

    /** The written token used to report errors in this expansion. */
    open val diagnosticOrigin: Token get() = diagnosticToken ?: this

    fun diagnosticSite(): SourceToken = diagnosticOrigin.physicalSite()

    private fun physicalSite(): SourceToken =
        SourceToken(Location(Path.of(originalProgram!!.filename), originalSourceLine), originalStartPos)

    fun setDiagnosticOrigin(origin: Token?) {
        if (origin != null) diagnosticToken = origin.diagnosticOrigin
    }

    fun writtenOrigin(): Token = expansionSource ?: this

    fun setExpansionSource(source: Token?) {
        expansionSource = source
    }

    fun setArgumentOrigin(argument: Token?) {
        argumentOrigin = argument
    }

    fun argumentSites(): List<SourceToken> {
        var argument = argumentOrigin ?: return emptyList()
        val sites = ArrayList<SourceToken>()
        while (true) {
            sites.add(argument.writtenOrigin().physicalSite())
            argument = argument.argumentOrigin ?: return sites
        }
    }

    fun equivalenceSource(): SourceToken? = equivalenceSource

    fun setEquivalenceSource(source: SourceToken?) {
        equivalenceSource = source
    }
}
