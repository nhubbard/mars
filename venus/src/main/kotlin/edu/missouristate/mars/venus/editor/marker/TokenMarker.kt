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

package edu.missouristate.mars.venus.editor.marker

import edu.missouristate.mars.venus.editor.PopupHelpItem
import javax.swing.text.Segment

/**
 * A token marker that splits lines of text into tokens. Each token carries
 * a length field and an identification tag that can be mapped to a color
 * for painting that token.
 *
 * For performance reasons, the linked list of tokens is reused after each
 * line is tokenized. Therefore, the return value of [markTokens]
 * should only be used for immediate painting. Notably, it cannot be
 * cached.
 *
 * @author Slava Pestov
 * @see edu.missouristate.mars.venus.editor.marker.Token
 */
abstract class TokenMarker protected constructor() {
    protected var firstToken: Token? = null
    protected var lastToken: Token? = null
    protected var lineInfo: Array<LineInfo>? = null
    protected var lineCount: Int = 0
    protected var lastLine: Int = -1
    var isNextLineRequested: Boolean = false

    /**
     * Wraps the lower-level [markTokensImpl], which is called to split a line into tokens.
     */
    fun markTokens(line: Segment, lineIndex: Int): Token {
        if (lineIndex >= lineCount) throw IllegalArgumentException("Tokenizing invalid line: $lineIndex")
        lastToken = null
        val info = lineInfo!![lineIndex]
        val prev: LineInfo? = if (lineIndex == 0) null else lineInfo!![lineIndex - 1]
        val oldType = info.type
        val type = markTokensImpl(prev?.type ?: Token.Type.NULL, line, lineIndex)
        info.type = type

        /*
         * This is a foul hack. It stops nextLineRequested from being cleared if
         * the same line is marked twice.
         *
         * Why is this necessary? It's all JEditTextArea's fault. When something
         * is inserted into the text, firing a document event, the
         * insertUpdate() method shifts the caret (if necessary) by the amount
         * inserted.
         *
         * All caret movement is handled by the select() method, which
         * eventually pipes the new position to scrollTo() and calls repaint().
         *
         * Note that at this point in time, the new line hasn't yet been
         * painted; the caret is moved first.
         *
         * scrollTo() calls offsetToX(), which tokenizes the line unless it is
         * being called on the last line painted (in which case it uses the text
         * area's painter cached type list). What scrollTo() does next is
         * irrelevant.
         *
         * After scrollTo() has done it's job, repaint() is called, and
         * eventually we end up in paintLine(), whose job is to paint the
         * changed line. It, too, calls markTokens().
         *
         * The problem was that if the line started a multiline type, the first
         * markTokens() (done in offsetToX()) would set nextLineRequested
         * (because the line end type had changed) but the second would clear
         * it (because the line was the same that time) and therefore
         * paintLine() would never know that it needed to repaint subsequent
         * lines.
         *
         * This bug took me ages to track down. That's why I wrote all the
         * relevant info down so that others wouldn't duplicate it.
         */
        if (!(lastLine == lineIndex && isNextLineRequested)) isNextLineRequested = (oldType != type)
        lastLine = lineIndex
        addToken(0, Token.Type.END)
        return firstToken!!
    }

    /**
     * An abstract method that splits a line up into tokens. It
     * should parse the line, and call <code>addToken()</code> to
     * add syntax tokens to the token list. Then, it should return
     * the initial token type for the next line.<p>
     * <p>
     * For example if the current line contains the start of a
     * multiline comment that doesn't end on that line, this method
     * should return the comment token type so that it continues on
     * the next line.
     *
     * @param type     The initial token type for this line
     * @param line      The line to be tokenized
     * @param lineIndex The index of the line in the document, starting at 0
     * @return The initial token type for the next line
     */
    protected abstract fun markTokensImpl(type: Token.Type, line: Segment, lineIndex: Int): Token.Type

    /**
     * Returns if the token marker supports tokens that span multiple
     * lines. If this is true, the object using this token marker is
     * required to pass all lines in the document to the
     * <code>markTokens()</code> method (in turn).<p>
     * <p>
     * The default implementation returns true; it should be overridden
     * to return false on simpler token markers for increased speed.
     */
    open fun supportsMultilineTokens(): Boolean = true

    /**
     * Informs the token marker that lines have been inserted into
     * the document. This inserts a gap in the <code>lineInfo</code>
     * array.
     *
     * @param index The first line number
     * @param lines The number of lines
     */
    fun insertLines(index: Int, lines: Int) {
        if (lines <= 0) return
        lineCount += lines
        ensureCapacity(lineCount)
        val len = index + lines
        System.arraycopy(lineInfo!!, index, lineInfo!!, len, lineInfo!!.size - len)
        for (i in (index + lines - 1) downTo index)
            lineInfo!![i] = LineInfo()
    }

    /**
     * Informs the token marker that line have been deleted from
     * the document. This removes the lines in question from the
     * `lineInfo` array.
     *
     * @param index The first line number
     * @param lines The number of lines
     */
    fun deleteLines(index: Int, lines: Int) {
        if (lines <= 0) return
        val len = index + lines
        lineCount -= lines
        System.arraycopy(lineInfo!!, len, lineInfo!!, index, lineInfo!!.size - len)
    }

    /**
     * Construct and return any appropriate help information for
     * the given token.  This default definition returns null;
     * override it in language-specific subclasses.
     *
     * @param token     the pertinent Token object
     * @param tokenText the source String that matched to the token
     * @return ArrayList containing PopupHelpItem objects, one per match.
     */
    open fun getTokenExactMatchHelp(token: Token?, tokenText: String?): ArrayList<PopupHelpItem>? = null

    /**
     * Construct and return any appropriate help information for
     * the given token or "token prefix". Will match instruction prefixes, e.g. "s" matches "sw".
     * This default definition returns null; override it in language-specific subclasses.
     *
     * @param line          String containing current line
     * @param tokenList     first Token on the current line
     * @param tokenAtOffset the pertinent Token object
     * @param tokenText     the source String that matched to the token
     * @return ArrayList containing PopupHelpItem objects, one per match.
     */
    open fun getTokenPrefixMatchHelp(
        line: String,
        tokenList: Token?,
        tokenAtOffset: Token?,
        tokenText: String
    ): ArrayList<PopupHelpItem>? = null

    /**
     * Ensures that the `lineInfo` array can contain the
     * specified index. This enlarges it if necessary. No action is
     * taken if the array is large enough already.
     *
     * It should be unnecessary to call this under normal
     * circumstances; `insertLine` should take care of
     * enlarging the line info array automatically.
     *
     * @param index The array index
     */
    protected fun ensureCapacity(index: Int) {
        if (lineInfo == null) lineInfo = Array(index + 1) { LineInfo() }
        else if (lineInfo!!.size <= index) {
            val lineInfoN = Array((index + 1) * 2) { LineInfo() }
            lineInfo!!.copyInto(lineInfoN)
            lineInfo = lineInfoN
        }
    }

    /**
     * Adds a token to the token list.
     *
     * @param length The length of the token
     * @param type     The type of the token
     */
    protected fun addToken(length: Int, type: Token.Type) {
        if (type.isInternal) throw InternalError("Invalid type: $type")
        if (length == 0 && type != Token.Type.END) return
        if (firstToken == null) {
            firstToken = Token(length, type)
            lastToken = firstToken
        } else if (lastToken == null) {
            lastToken = firstToken
            firstToken!!.length = length
            firstToken!!.type = type
        } else if (lastToken!!.next == null) {
            lastToken!!.next = Token(length, type)
            lastToken = lastToken!!.next
        } else {
            lastToken = lastToken!!.next
            lastToken!!.length = length
            lastToken!!.type = type
        }
    }

    data class LineInfo @JvmOverloads constructor(
        var type: Token.Type = Token.Type.NULL,
        var obj: Any? = null
    )
}