/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.assembler

import edu.missouristate.mars.MIPSProgram

/**
 * Stores macros defined by this point in compilation.
 * Will be used in first pass of assembling MIPS source code.
 * When the `.macro` directive is reached,
 * the parser calls [beginMacro] and skips source code lines until it reaches the `.end_macro` directive.
 * It then calls [commitMacro], and the macro information stored in a [Macro] instance will be added to [macroList].
 * Each [MIPSProgram] will have one [MacroPool].
 *
 * **Warning:** Forward referencing macros (macro expansion before the macro is defined in source code) and nested macro
 * definitions are not supported!
 *
 * @param program The associated MIPS program.
 */
class MacroPool(private val program: MIPSProgram) {
    /** The list of defined macros */
    internal val macroList: ArrayList<Macro> = arrayListOf()
    /** @see beginMacro */
    var current: Macro? = null
    private val callStack: ArrayList<Int> = arrayListOf()
    private val callStackOrigLines: ArrayList<Int> = arrayListOf()
    /** @see getNextCounter */
    private var counter: Int = 0

    /**
     * The parser calls this method when the `.macro` directive is reached.
     * It creates a new [Macro] instance and stores it in [current].
     * [current] will be added to [macroList] by [commitMacro].
     *
     * @param nameToken The [Token] containing the name of the macro specified after the `.macro` directive.
     */
    fun beginMacro(nameToken: Token) {
        current = Macro()
        current!!.apply {
            name = nameToken.value
            fromLine = nameToken.sourceLine
            originalFromLine = nameToken.originalSourceLine
            program = this@MacroPool.program
        }
    }

    /**
     * The parser calls this method when the `.end_macro` directive is reached.
     * It adds or replaces the current macro in [macroList].
     */
    fun commitMacro(endToken: Token) {
        requireNotNull(current)
        current!!.toLine = endToken.sourceLine
        current!!.originalToLine = endToken.originalSourceLine
        current!!.readyForCommit()
        macroList.add(current!!)
        current = null
    }

    /**
     * The parser calls this method when it reaches a macro expansion call.
     *
     * @param tokens The Tokens passed to the macro expansion call.
     * @return Nullable [Macro] object matching the name and argument count of tokens passed
     */
    fun getMatchingMacro(tokens: TokenList): Macro? {
        if (tokens.isEmpty()) return null
        var ret: Macro? = null
        val firstToken = tokens[0]
        for (macro in macroList) {
            if (macro.name == firstToken.value &&
                macro.args.size + 1 == tokens.size &&
                (ret == null || ret.fromLine < macro.fromLine)) ret = macro
        }
        return ret
    }

    /**
     * @return `true` if any macros have been defined with the name `value`; does not check argument counts.
     */
    fun matchesAnyMacroName(value: String): Boolean = macroList.any { it.name == value }

    /**
     * `counter` will be set to 0 on construction of this class and will be incremented by each call.
     * The parser calls this method once for every expansion.
     * It will be a unique ID for each expansion of a macro in a file.
     *
     * @return Counter value.
     */
    fun getNextCounter() = counter++

    /**
     * Adds a token to the call stack.
     *
     * @return `true` if there is a macro expansion loop (very bad), `false` otherwise
     */
    fun pushOnCallStack(token: Token): Boolean {
        val sourceLine = token.sourceLine
        val origSourceLine = token.originalSourceLine
        if (callStack.contains(sourceLine)) return true
        callStack.add(sourceLine)
        callStackOrigLines.add(origSourceLine)
        return false
    }

    /**
     * Remove a token from the top of the call stack.
     */
    fun popFromCallStack() {
        callStack.removeLastOrNull()
        callStackOrigLines.removeLastOrNull()
    }

    /**
     * Get the macro expansion history as a String.
     */
    fun getExpansionHistory(): String = buildString {
        for (i in callStackOrigLines.indices) {
            if (i > 0) append("->")
            append(callStackOrigLines[i].toString())
        }
    }
}