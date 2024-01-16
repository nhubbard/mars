/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
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

@file:Suppress("NAME_SHADOWING")

package edu.missouristate.mars.venus.editor

import javax.swing.text.BadLocationException
import javax.swing.text.Document

/** Utility functions used by the text area component. */
object TextUtilities {
    /**
     * Returns the offset of the bracket matching the one at the
     * specified offset of the document, or -1 if the bracket is
     * unmatched (or if the character is not a bracket).
     *
     * @param offset The offset
     * @throws BadLocationException If out-of-bounds access was attempted on the document text
     */
    @JvmStatic
    @Throws(BadLocationException::class)
    fun Document.findMatchingBracket(offset: Int): Int {
        var offset = offset
        if (length == 0) return -1
        val c = getText(offset, 1)[0]
        // For direction: true is back, false is forward
        val (cPrime, direction) = when (c) {
            '(' -> ')' to false
            ')' -> '(' to true
            '[' -> ']' to false
            ']' -> '[' to true
            '{' -> '}' to false
            '}' -> '{' to true
            else -> return -1
        }
        var count: Int
        // Merging these two cases is left as an exercise for the reader.
        // Go back or forward.
        if (direction) {
            // The count is 1 initially because we have already 'found' one closing bracket.
            count = 1
            // Get text[0..<offset]
            val text = getText(0, offset)
            // Scan backwards
            for (i in text.indices.reversed()) {
                // If text[i] == c, we have found another closing bracket, and we'll need two opening brackets to
                // complete the match.
                val x = text[i]
                if (x == c) {
                    count++
                } else if (x == cPrime) {
                    // If text[i] == cPrime, we have found an opening bracket, so we return i if --count == 0.
                    if (--count == 0) return i
                }
            }
        } else {
            // The count is 1 initially because we have already 'found' one opening bracket.
            count = 1
            // For convenience, so we don't have to do `[n] + 1` in every loop
            offset++
            // Get the number of characters to check
            val len = length - offset
            // Get text[offset + 1..len]
            val text = getText(offset, len)
            // Scan forwards
            for (i in 0..<len) {
                // If text[i] == c, we have found another opening bracket, and we will need two closing brackets to
                // complete the match.
                val x = text[i]
                if (x == c) {
                    count++
                } else if (x == cPrime) {
                    // If text[i] == cPrime, we have found a closing bracket, so we return i if --count == 0
                    if (--count == 0) return i + offset
                }
            }
        }
        // Nothing was found!
        return -1
    }

    /**
     * Locates the start of the word at the specified position.
     *
     * @param pos The position.
     */
    @JvmStatic
    @JvmOverloads
    fun String.findWordStart(pos: Int, noWordSep: String = ""): Int {
        var ch = this[pos - 1]
        val selectNoLetter = !ch.isLetterOrDigit() && noWordSep.indexOf(ch) == -1
        var wordStart = 0
        for (i in (pos - 1) downTo 0) {
            ch = this[i]
            if (selectNoLetter xor (!ch.isLetterOrDigit() && noWordSep.indexOf(ch) == -1)) {
                wordStart = i + 1
                break
            }
        }
        return wordStart
    }

    /**
     * Locates the end of the word at the specified position.
     *
     * @param pos The position.
     */
    @JvmStatic
    @JvmOverloads
    fun String.findWordEnd(pos: Int, noWordSep: String = ""): Int {
        var ch = this[pos]
        val selectNoLetter = !ch.isLetterOrDigit() && noWordSep.indexOf(ch) == -1
        var wordEnd = length
        for (i in pos..<length) {
            ch = this[i]
            if (selectNoLetter xor (!ch.isLetterOrDigit() && noWordSep.indexOf(ch) == -1)) {
                wordEnd = i
                break
            }
        }
        return wordEnd
    }
}