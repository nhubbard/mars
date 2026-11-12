package edu.missouristate.mars

import edu.missouristate.mars.assembler.Token

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

/** An assembler or simulator diagnostic at its original written source. */
open class ErrorMessage private constructor(
    open val isWarning: Boolean,
    open val filename: String?,
    open val line: Int,
    open val position: Int,
    open val message: String?,
    private val expansionHistory: String?,
) {
    constructor(sourceMIPSprogram: MIPSprogram?, line: Int, position: Int, message: String?) :
        this(ERROR, sourceMIPSprogram, line, position, message)

    constructor(token: Token, message: String?) : this(ERROR, token, message)

    constructor(isWarning: Boolean, token: Token, message: String?) :
        this(isWarning, message, token.diagnosticOrigin)

    private constructor(isWarning: Boolean, message: String?, origin: Token) :
        this(isWarning, origin.sourceMIPSprogram, origin.sourceLine, origin.originalStartPos, message)

    constructor(isWarning: Boolean, sourceMIPSprogram: MIPSprogram?, line: Int, position: Int, message: String?) :
        this(isWarning, assemblyLine(sourceMIPSprogram, line), position, message, sourceMIPSprogram?.localMacroPool?.expansionHistory)

    private constructor(isWarning: Boolean, source: WrittenLine, position: Int, message: String?, history: String?) :
        this(isWarning, source.filename, source.line, position, message, history)

    /** Recover the macro definition's line from the source attached to a runtime instruction. */
    constructor(statement: ProgramStatement, message: String?) :
        this(statement, message, firstMacroLine(statement.source!!))

    private constructor(statement: ProgramStatement, message: String?, definitionLine: Int?) : this(
        ERROR, statement.sourceFile, definitionLine ?: statement.sourceLine, 0, message,
        if (definitionLine == null) "" else statement.sourceLine.toString(),
    )

    open val macroExpansionHistory: String
        get() = if (expansionHistory.isNullOrEmpty()) "" else "$expansionHistory->"

    private data class WrittenLine(val filename: String?, val line: Int)

    companion object {
        const val WARNING = true
        const val ERROR = false
        private val macroLine = Regex("<\\d+>")

        private fun assemblyLine(program: MIPSprogram?, line: Int): WrittenLine {
            if (program == null) return WrittenLine("", line)
            val original = program.sourceLineList?.get(line - 1) ?: return WrittenLine(program.filename, line)
            return WrittenLine(original.filename, original.lineNumber)
        }

        private fun firstMacroLine(source: String): Int? =
            macroLine.matchAt(source.trim { it <= ' ' }, 0)?.value?.drop(1)?.dropLast(1)?.toIntOrNull()
    }
}
