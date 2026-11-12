package edu.missouristate.mars.assembler

import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.api.AssemblySymbolMap.MacroDefinition

/*
Copyright (c) 2013.

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

/** Macro definitions and the active expansion stack for one source program. */
open class MacroPool(private val program: MIPSprogram) {
    private val macros = ArrayList<Macro>()
    private val originalCallLines = ArrayList<Int>()
    private var counter = 0

    open var current: Macro? = null
    open val callStack = ArrayList<Int>()

    open fun beginMacro(nameToken: Token) {
        current = Macro().apply {
            name = nameToken.value
            fromLine = nameToken.sourceLine
            originalFromLine = nameToken.originalSourceLine
            nameColumn = nameToken.writtenOrigin().originalStartPos
            program = this@MacroPool.program
        }
    }

    open fun commitMacro(endToken: Token) {
        val macro = current!!
        macro.toLine = endToken.sourceLine
        macro.originalToLine = endToken.originalSourceLine
        macro.readyForCommit()
        macros.add(macro)
        current = null
    }

    @Suppress("UNUSED_PARAMETER")
    open fun getMatchingMacro(tokens: TokenList, callerLine: Int): Macro? {
        if (tokens.isEmpty) return null
        var result: Macro? = null
        val name = tokens[0].value
        for (macro in macros) {
            if (macro.name == name && macro.args.size + 1 == tokens.size() &&
                (result == null || result.fromLine < macro.fromLine)
            ) result = macro
        }
        return result
    }

    open fun matchesAnyMacroName(value: String): Boolean = macros.any { it.name == value }

    fun definitions(checkCancellation: Runnable): List<MacroDefinition> =
        macros.map { macro ->
            checkCancellation.run()
            MacroDefinition(macro.definitionSite(), macro.name, macro.args)
        }

    open fun getNextCounter(): Int = counter++

    /** Return true for recursion, leaving the stack as it was. */
    open fun pushOnCallStack(token: Token): Boolean {
        val line = token.sourceLine
        if (line in callStack) return true
        callStack.add(line)
        originalCallLines.add(token.originalSourceLine)
        return false
    }

    open fun popFromCallStack() {
        callStack.removeLast()
        originalCallLines.removeLast()
    }

    open val expansionHistory: String get() = originalCallLines.joinToString("->")
}
