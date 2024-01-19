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

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.util.Binary
import java.awt.*
import java.awt.image.BufferedImage

/** A simple text style class. Specifies color, bold, and italic properties for a run of text. */
class SyntaxStyle(val color: Color?, val isItalic: Boolean, val isBold: Boolean) {
    private var lastFont: Font? = null
    private var lastStyledFont: Font? = null
    private var fontMetrics: FontMetrics? = null

    /** Get [color] as a hex string. Used by Settings class to avoid direct use of [Color] in headless mode. */
    val colorAsHexString: String
        get() = color?.let {
            Binary.intToHexString((it.red shl 16) or (it.green shl 8) or it.blue)
        } ?: "0x000000"

    val isPlain: Boolean
        get() = !(isBold || isItalic)

    fun getStyledFont(font: Font?): Font {
        requireNotNull(font) { "Font param must not be null!" }
        if (font == lastFont) return lastStyledFont!!
        lastFont = font
        lastStyledFont = Font(
            font.family,
            (if (isBold) Font.BOLD else Font.PLAIN) or (if (isItalic) Font.ITALIC else Font.PLAIN),
            font.size
        )
        return lastStyledFont!!
    }

    fun getFontMetrics(font: Font?): FontMetrics {
        requireNotNull(font) { "Font param must not be null!" }
        if (font == lastFont && fontMetrics != null) return fontMetrics!!
        lastFont = font
        lastStyledFont = Font(
            font.family,
            (if (isBold) Font.BOLD else Font.PLAIN) or (if (isItalic) Font.ITALIC else Font.PLAIN),
            font.size
        )
        // The next bit is a workaround for the previous use of Toolkit.getDefaultToolkit().getFontMetrics(Font),
        // which has been deprecated since Java 1.2.
        // Create a temporary BufferedImage and get its Graphics2D context
        val tempImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2d = tempImage.createGraphics()
        // Use the Graphics2D context to get FontMetrics
        fontMetrics = g2d.getFontMetrics(lastStyledFont)
        // It's important to dispose of the Graphics2D context to free resources
        g2d.dispose()
        return fontMetrics!!
    }

    fun setGraphicsFlags(gfx: Graphics, font: Font) {
        val styledFont = getStyledFont(font)
        gfx.font = styledFont
        gfx.color = color
    }

    override fun toString(): String =
        "SyntaxStyle[color=$color${if (isItalic) ",italic" else ""}${if (isBold) ",bold" else ""}]"
}