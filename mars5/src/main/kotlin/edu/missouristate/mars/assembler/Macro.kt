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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.MIPSProgram
import edu.missouristate.mars.mips.hardware.Coprocessor0
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.RegisterFile
import java.util.*
import kotlin.collections.ArrayList

/** Stores information of a macro definition. */
class Macro {
    var name: String = ""
    var program: MIPSProgram? = null
    var fromLine: Int = 0
    var toLine: Int = 0
    var originalFromLine: Int = 0
    var originalToLine: Int = 0
    var args: ArrayList<String> = arrayListOf()

    private val labels: ArrayList<String> = arrayListOf()

    override fun equals(other: Any?): Boolean {
        if (other is Macro) return other.name == name && other.args.size == args.size
        return super.equals(other)
    }

    fun addArg(value: String) { args.add(value) }

    /**
     * Substitutes macro arguments in a line of source code inside macro
     * definition to be parsed after macro expansion.
     * Also appends "_M#" to all labels defined inside the macro body, where # is value of `counter`
     *
     * @param line    source line number in macro definition to be substituted
     * @param counter unique macro expansion id
     * @return `line`-th line of source code, with substituted arguments
     */
    fun getSubstitutedLine(line: Int, args: TokenList, counter: Int, errors: ErrorList): String {
        requireNotNull(program)
        val tokens = program!!.getTokenList()[line - 1]
        var s = program!!.getSourceLine(line)!!
        for (i in tokens.size - 1 downTo 0) {
            val token = tokens[i]
            if (tokenIsMacroParameter(token.value, true)) {
                val repl = this.args.indices.firstOrNull { this.args[it] == token.value } ?: -1
                var substitute = token.value
                if (repl != -1) substitute = args[repl + 1].toString()
                else errors.add(ErrorMessage(program, token.sourceLine, token.startPosition, "Unknown macro parameter!"))
                s = replaceToken(s, token, substitute)
            } else if (tokenIsMacroLabel(token.value)) {
                val substitute = "${token.value}_M$counter"
                s = replaceToken(s, token, substitute)
            }
        }
        return s
    }

    /**
     * @return `true` if `value` is the name of a label defined in this macro's body.
     */
    private fun tokenIsMacroLabel(value: String): Boolean = Collections.binarySearch(labels, value) >= 0

    /**
     * Replace the token `tokenToBeReplaced` where it occurs in `source` with `substitute`.
     */
    private fun replaceToken(source: String, tokenToBeReplaced: Token, substitute: String): String {
        val stringToBeReplaced = tokenToBeReplaced.value
        return source.replace(stringToBeReplaced, substitute)
    }

    fun addLabel(value: String) { labels.add(value) }

    fun readyForCommit() { labels.sort() }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (program?.hashCode() ?: 0)
        result = 31 * result + fromLine
        result = 31 * result + toLine
        result = 31 * result + originalFromLine
        result = 31 * result + originalToLine
        result = 31 * result + args.hashCode()
        result = 31 * result + labels.hashCode()
        return result
    }

    companion object {
        /**
         * Return whether `tokenValue` is a macro parameter or not.
         */
        @JvmStatic
        fun tokenIsMacroParameter(tokenValue: String, acceptSpimStyleParameters: Boolean): Boolean =
            if (acceptSpimStyleParameters)
                tokenValue.isNotEmpty() &&
                tokenValue[0] == '$' &&
                RegisterFile.getUserRegister(tokenValue) == null &&
                Coprocessor0.getRegister(tokenValue) == null &&
                Coprocessor1.getRegister(tokenValue) == null
            else tokenValue.length > 1 && tokenValue[0] == '%'
    }
}