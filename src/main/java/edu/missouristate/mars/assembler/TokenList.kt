/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 */

@file:Suppress("UNCHECKED_CAST")

package edu.missouristate.mars.assembler

/**
 * Represents the list of token in a single line of MIPS code.
 *
 * @note This class previously was intentionally made not to subclass ArrayList. This has changed with the Kotlin
 * conversion; TokenList now implements the MutableList<Token> interface to receive all the convenient extensions
 * provided by it.
 */
class TokenList: Cloneable, MutableList<Token> {
    private var tokenList: ArrayList<Token> = arrayListOf()

    /**
     * The source code associated with this token list.
     */
    var processedLine: String = ""

    // Implement MutableList<Token> interface requirements.
    override val size: Int get() = tokenList.size
    override fun clear() = tokenList.clear()
    override fun addAll(elements: Collection<Token>): Boolean = tokenList.addAll(elements)
    override fun addAll(index: Int, elements: Collection<Token>): Boolean = tokenList.addAll(index, elements)
    override fun add(index: Int, element: Token) = tokenList.add(index, element)
    override fun add(element: Token): Boolean = tokenList.add(element)
    override fun get(index: Int): Token = tokenList[index]
    override fun isEmpty(): Boolean = tokenList.isEmpty()
    override fun iterator(): MutableIterator<Token> = tokenList.iterator()
    override fun listIterator(): MutableListIterator<Token> = tokenList.listIterator()
    override fun listIterator(index: Int): MutableListIterator<Token> = tokenList.listIterator(index)
    override fun removeAt(index: Int): Token = tokenList.removeAt(index)
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<Token> = tokenList.subList(fromIndex, toIndex)
    override fun set(index: Int, element: Token): Token = tokenList.set(index, element)
    override fun retainAll(elements: Collection<Token>): Boolean = tokenList.retainAll(elements.toSet())
    override fun removeAll(elements: Collection<Token>): Boolean = tokenList.removeAll(elements.toSet())
    override fun remove(element: Token): Boolean = tokenList.remove(element)
    override fun lastIndexOf(element: Token): Int = tokenList.lastIndexOf(element)
    override fun indexOf(element: Token): Int = tokenList.indexOf(element)
    override fun containsAll(elements: Collection<Token>): Boolean = tokenList.containsAll(elements)
    override fun contains(element: Token): Boolean = tokenList.contains(element)

    // Replace toString and add secondary implementation where types instead of Tokens are returned.
    override fun toString(): String = buildString {
        tokenList.forEach { append(it.toString()).append(" ") }
    }

    fun toTypeString(): String = buildString {
        tokenList.forEach { append(it.type).append(" ") }
    }

    // Implement Cloneable interface requirement(s).
    public override fun clone(): Any {
        val t = super.clone() as TokenList
        t.tokenList = tokenList.clone() as ArrayList<Token>
        return t
    }
}