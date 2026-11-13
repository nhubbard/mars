package edu.missouristate.mars.assembler

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

/** Tokens for one source line, with the text and status of equivalence expansion. */
open class TokenList : Cloneable {
    private var tokens = ArrayList<Token>()
    private var equivalenceExpansionIncomplete = false

    open var processedLine: String = ""

    open fun hasCompleteEquivalenceExpansion(): Boolean = !equivalenceExpansionIncomplete

    fun markEquivalenceExpansionIncomplete() {
        equivalenceExpansionIncomplete = true
    }

    open operator fun get(position: Int): Token = tokens[position]

    open operator fun set(position: Int, replacement: Token) {
        tokens[position] = replacement
    }

    open fun size(): Int = tokens.size

    open fun add(token: Token) {
        tokens.add(token)
    }

    open fun remove(position: Int) {
        tokens.removeAt(position)
    }

    open val isEmpty: Boolean get() = tokens.isEmpty()

    open fun beginsWithLabel(): Boolean =
        size() >= 2 && (get(0).type == TokenTypes.IDENTIFIER || get(0).type == TokenTypes.OPERATOR) &&
            get(1).type == TokenTypes.COLON

    open fun hasNumericRepetitionShape(): Boolean =
        (size() == 4 || size() == 5 && get(4).type == TokenTypes.COMMENT) && get(2).type == TokenTypes.COLON

    override fun toString(): String = buildString {
        for (token in tokens) append(token.toString()).append(' ')
    }

    public override fun clone(): Any = (super.clone() as TokenList).also { it.tokens = ArrayList(tokens) }
}
