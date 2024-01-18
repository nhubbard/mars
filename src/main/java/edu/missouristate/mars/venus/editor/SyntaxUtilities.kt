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

import edu.missouristate.mars.Globals.settings
import edu.missouristate.mars.venus.editor.marker.Token
import edu.missouristate.mars.venus.editor.marker.Token.Type.*
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.text.Segment
import javax.swing.text.TabExpander
import javax.swing.text.Utilities

object SyntaxUtilities {
    @Deprecated(
        "Use reordered version instead.",
        ReplaceWith(
            "text.regionMatches(match, offset, ignoreCase)",
            "edu.missouristate.mars.venus.editor.KSyntaxUtilities.regionMatches"
        ),
        DeprecationLevel.ERROR
    )
    @JvmStatic
    fun regionMatches(ignoreCase: Boolean, text: Segment, offset: Int, match: String): Boolean =
        text.regionMatches(match, offset, ignoreCase)

    /**
     * Checks if a subregion of a [Segment] is equal to a string.
     *
     * @param match      The string to match
     * @param offset     The offset into the segment
     * @param ignoreCase True if the case should be ignored, false otherwise
     */
    @JvmStatic
    @JvmOverloads
    fun Segment.regionMatches(match: String, offset: Int = 0, ignoreCase: Boolean = false): Boolean {
        val length = offset + match.length
        val textArray = array
        if (length > offset + count) return false
        var i = offset
        var j = 0
        while (i < length) {
            var c1 = textArray[i]
            var c2 = match[j]
            if (ignoreCase) {
                c1 = c1.uppercaseChar()
                c2 = c2.uppercaseChar()
            }
            if (c1 != c2) return false
            i++
            j++
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun Segment.regionMatches(match: CharArray, offset: Int = 0, ignoreCase: Boolean = false): Boolean =
        regionMatches(match.concatToString(), offset, ignoreCase)

    /**
     * Returns the default style table. This can be passed to the
     * <code>setStyles()</code> method of <code>SyntaxDocument</code>
     * to use the default syntax styles.
     */
    @JvmStatic
    fun getDefaultSyntaxStyles(): Array<SyntaxStyle> =
        Array(Token.Type.entries.size) {
            when (it.toByte()) {
                NULL.rawValue      -> SyntaxStyle(Color.black, isItalic = false, isBold = false)
                COMMENT1.rawValue  -> SyntaxStyle(Color(0x00cc33), isItalic = true, isBold = false)
                COMMENT2.rawValue  -> SyntaxStyle(Color(0x990033), isItalic = true, isBold = false)
                KEYWORD1.rawValue  -> SyntaxStyle(Color.blue, isItalic = false, isBold = false)
                KEYWORD2.rawValue  -> SyntaxStyle(Color.magenta, isItalic = false, isBold = false)
                KEYWORD3.rawValue  -> SyntaxStyle(Color.red, isItalic = false, isBold = false)
                LITERAL1.rawValue  -> SyntaxStyle(Color(0x00cc33), isItalic = false, isBold = false)
                LITERAL2.rawValue  -> SyntaxStyle(Color(0x00cc33), isItalic = false, isBold = false)
                LABEL.rawValue     -> SyntaxStyle(Color.black, isItalic = true, isBold = false)
                OPERATOR.rawValue  -> SyntaxStyle(Color.black, isItalic = false, isBold = true)
                INVALID.rawValue   -> SyntaxStyle(Color.red, isItalic = false, isBold = false)
                MACRO_ARG.rawValue -> SyntaxStyle(Color(150, 150, 0), isItalic = false, false)
                // Handle all other types
                else               -> SyntaxStyle(Color.white, isItalic = false, isBold = false)
            }
        }

    /**
     * Returns the CURRENT style table. This can be passed to the
     * <code>setStyles()</code> method of <code>SyntaxDocument</code>
     * to use the current syntax styles.  If changes have been made
     * via MARS Settings menu, the current settings will not be the
     * same as the default settings.
     */
    @JvmStatic
    fun getCurrentSyntaxStyles(): Array<SyntaxStyle> =
        Array(Token.Type.entries.size) {
            when (it.toByte()) {
                NULL.rawValue      -> settings.getEditorSyntaxStyle(NULL)
                COMMENT1.rawValue  -> settings.getEditorSyntaxStyle(COMMENT1)
                COMMENT2.rawValue  -> settings.getEditorSyntaxStyle(COMMENT2)
                KEYWORD1.rawValue  -> settings.getEditorSyntaxStyle(KEYWORD1)
                KEYWORD2.rawValue  -> settings.getEditorSyntaxStyle(KEYWORD2)
                KEYWORD3.rawValue  -> settings.getEditorSyntaxStyle(KEYWORD3)
                LITERAL1.rawValue  -> settings.getEditorSyntaxStyle(LITERAL1)
                LITERAL2.rawValue  -> settings.getEditorSyntaxStyle(LITERAL2)
                LABEL.rawValue     -> settings.getEditorSyntaxStyle(LABEL)
                OPERATOR.rawValue  -> settings.getEditorSyntaxStyle(OPERATOR)
                INVALID.rawValue   -> settings.getEditorSyntaxStyle(INVALID)
                MACRO_ARG.rawValue -> settings.getEditorSyntaxStyle(MACRO_ARG)
                // Handle all other types
                else               -> SyntaxStyle(Color.white, isItalic = false, isBold = false)
            }
        }

    @Deprecated(
        "Use extension method instead.",
        ReplaceWith(
            "gfx.paintSyntaxLine(x.toFloat() to y.toFloat(), line, tokens, styles, expander)",
            "edu.missouristate.mars.venus.editor.KSyntaxUtilities.paintSyntaxLine"
        ),
        DeprecationLevel.ERROR
    )
    @JvmStatic
    fun paintSyntaxLine(
        line: Segment,
        tokens: Token,
        styles: Array<SyntaxStyle>,
        expander: TabExpander,
        gfx: Graphics,
        x: Int,
        y: Int
    ): Int = gfx.paintSyntaxLine(x.toFloat() to y.toFloat(), line, tokens, styles, expander).toInt()

    /**
     * Paints the specified line onto the graphics context. Note that this
     * method will mess with the offset and count values of the segment.
     *
     * @param at       The (x, y) coordinate pair
     * @param line     The line segment
     * @param tokens   The token list for the line
     * @param styles   The syntax style list
     * @param expander The tab expander used to determine tab stops. May
     *                 be null.
     * @return The x co-ordinate, plus the width of the painted string
     */
    @JvmStatic
    fun Graphics.paintSyntaxLine(
        at: Pair<Float, Float>,
        line: Segment,
        tokens: Token?,
        styles: Array<SyntaxStyle>,
        expander: TabExpander,
    ): Float {
        var tokens = tokens
        var (x, y) = at
        val defaultFont = font
        val defaultColor = color
        var offset = 0
        while (true) {
            val type = tokens?.type
            if (type == Token.Type.END) break
            val length = tokens?.length
            if (type == Token.Type.NULL) {
                if (defaultColor != color) color = defaultColor
                if (defaultFont != font) font = defaultFont
            } else styles[type?.rawValue?.toInt() ?: NULL.rawValue.toInt()].setGraphicsFlags(this, defaultFont)
            line.count = length ?: 0
            x = Utilities.drawTabbedText(line, x, y, this as Graphics2D, expander, 0)
            line.offset += length ?: 0
            offset += length ?: 0
            tokens = tokens?.next ?: break
        }
        return x
    }
}