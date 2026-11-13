package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import java.util.NavigableMap
import java.util.TreeMap
import kotlin.jvm.JvmRecord

/*
Copyright (c) 2013-2014.

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

/** One macro definition and its written parameter and label spellings. */
open class Macro {
    open var name = ""
    open var program: MIPSprogram? = null
    open var fromLine = 0
    open var toLine = 0
    open var originalFromLine = 0
    open var originalToLine = 0
    @JvmField var nameColumn = 0
    open var args = ArrayList<String>()
    private val labels = ArrayList<String>()

    fun definitionSite(): SourceToken {
        val header = program!!.sourceLineList!![fromLine - 1]
        return SourceToken(Parser.origin(header, null).location, nameColumn)
    }

    override fun equals(other: Any?): Boolean =
        other is Macro && other.name == name && other.args.size == args.size

    override fun hashCode(): Int = 31 * name.hashCode() + args.size

    open fun addArg(value: String) {
        args.add(value)
    }

    @JvmRecord
    data class TokenOrigin(val written: Token, val diagnostic: Token)

    @JvmRecord
    data class ExpandedLine(val text: String, val renamedLabels: Set<String>, val origins: NavigableMap<Int, TokenOrigin>) {
        fun tokenOriginAt(column: Int): TokenOrigin? = origins.ceilingEntry(text.length - column + 1)?.value

        fun tokenOriginStartingAt(column: Int): TokenOrigin? = origins[text.length - column + 1]

        fun originAt(column: Int): Token? = tokenOriginAt(column)?.diagnostic
    }

    fun expandLine(line: Int, args: TokenList, counter: Long, privateArguments: Set<String>, errors: ErrorList): ExpandedLine {
        val tokens = program!!.tokenList!![line - 1]
        val source = StringBuilder(tokens.processedLine)
        var renamedLabels: HashSet<String>? = null
        val origins = TreeMap<Int, TokenOrigin>()

        // Replace right to left. Origins use distances from the end, which stay valid across later replacements.
        for (index in tokens.size() - 1 downTo 0) {
            val token = tokens[index]
            val start = token.startPos - 1
            var origin = token
            val substitute: String
            val renamed: Boolean
            if (tokenIsMacroParameter(token.value, true)) {
                val argument = this.args.indexOf(token.value)
                if (argument >= 0) {
                    origin = args[argument + 1]
                    substitute = origin.toString()
                } else {
                    substitute = token.value
                    errors.add(ErrorMessage(token, "Unknown macro parameter"))
                }
                renamed = argument >= 0 && substitute in privateArguments
            } else if (labels.binarySearch(token.value) >= 0) {
                substitute = token.value + "_M" + counter
                renamed = true
            } else {
                origins[source.length - start] = TokenOrigin(token, origin)
                continue
            }
            if (renamed) {
                val renamedNames = renamedLabels ?: HashSet<String>().also { renamedLabels = it }
                renamedNames.add(substitute)
            }
            source.replace(start, token.endPos - 1, substitute)
            origins[source.length - start] = TokenOrigin(token, origin)
        }
        return ExpandedLine(source.toString(), renamedLabels ?: emptySet(), origins)
    }

    open fun addLabel(value: String) {
        labels.add(value)
    }

    open fun readyForCommit() {
        labels.sort()
    }

    companion object {
        @JvmStatic
        fun tokenIsMacroParameter(value: String, acceptSpimStyleParameters: Boolean): Boolean {
            if (acceptSpimStyleParameters && value.startsWith('$') && RegisterNames.integer(value) == null &&
                RegisterNames.coprocessor0(value) == null && RegisterNames.floating(value) == null
            ) return true
            return value.length > 1 && value[0] == '%'
        }

        @JvmStatic
        @JvmOverloads
        fun hasCallParentheses(tokens: TokenList, operationIndex: Int = 0): Boolean =
            tokens.size() - operationIndex > 2 && tokens[operationIndex + 1].type == TokenTypes.LEFT_PAREN &&
                tokens[tokens.size() - 1].type == TokenTypes.RIGHT_PAREN
    }
}
