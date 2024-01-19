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

@file:Suppress("MemberVisibilityCanBePrivate", "NAME_SHADOWING")

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.venus.editor.SyntaxUtilities.paintSyntaxLine
import edu.missouristate.mars.venus.editor.marker.Token
import edu.missouristate.mars.venus.editor.marker.TokenMarker
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.ToolTipManager
import javax.swing.text.Segment
import javax.swing.text.TabExpander
import javax.swing.text.Utilities
import kotlin.math.min

/** The text area repaint manager. Performs double buffering and paints lines of text. */
class TextAreaPainter(
    private val textArea: JEditTextArea,
    defaults: TextAreaDefaults
) : JComponent(), TabExpander {
    var currentLineIndex: Int
    var currentLineTokens: Token? = null
    val currentLine: Segment

    /** The styles of text. */
    var styles: Array<SyntaxStyle> = arrayOf()
        set(value) {
            field = value
            repaint()
        }

    /** Control the caret color. */
    var caretColor: Color = Color.black
        set(value) {
            field = value
            invalidateSelectedLines()
        }

    /** Control the color of selected text. */
    var selectionColor: Color = Color.blue
        set(value) {
            field = value
            invalidateSelectedLines()
        }

    /** Control the color that the current line is highlighted in. */
    var lineHighlightColor: Color = Color.yellow
        set(value) {
            field = value
            invalidateSelectedLines()
        }

    /** Control whether the current line is highlighted. */
    var isLineHighlightEnabled: Boolean = true
        set(value) {
            field = value
            invalidateSelectedLines()
        }

    /** Control what color brackets are highlighted as. */
    var bracketHighlightColor: Color = Color.green
        set(value) {
            field = value
            invalidateLine(textArea.bracketLine)
        }

    /** Control whether bracket highlighting is enabled. */
    var isBracketHighlightingEnabled: Boolean = true
        set(value) {
            field = value
            invalidateLine(textArea.bracketLine)
        }

    /** Control whether block selection mode for the caret is enabled. */
    var isBlockCaretEnabled: Boolean = false
        set(value) {
            field = value
            invalidateSelectedLines()
        }

    /** The color of the EOL markers, if [isEolMarkersEnabled] is set to true. */
    var eolMarkerColor: Color = Color.lightGray
        set(value) {
            field = value
            repaint()
        }

    /** Control whether EOL markers are painted. */
    var isEolMarkersEnabled: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    /** Control whether to paint invalid lines as red tildes (~). */
    var isPaintingInvalidLines: Boolean

    private val cols: Int
    private val rows: Int
    private var internalTabSize: Int = -1
    var tabSize: Int

    lateinit var fontMetrics: FontMetrics
        private set

    private var highlights: Highlight? = null

    init {
        autoscrolls = true
        isDoubleBuffered = true
        isOpaque = true

        ToolTipManager.sharedInstance().registerComponent(this)

        currentLine = Segment()
        currentLineIndex = -1

        cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)

        font = Font("Courier New", Font.PLAIN, 14)
        foreground = Color.black
        background = Color.white

        tabSize = defaults.tabSize
        isBlockCaretEnabled = defaults.blockCaret
        styles = defaults.styles
        cols = defaults.cols
        rows = defaults.rows
        caretColor = defaults.caretColor
        selectionColor = defaults.selectionColor
        lineHighlightColor = defaults.lineHighlightColor
        isLineHighlightEnabled = defaults.lineHighlight
        bracketHighlightColor = defaults.bracketHighlightColor
        isBracketHighlightingEnabled = defaults.bracketHighlight
        isPaintingInvalidLines = defaults.paintInvalid
        eolMarkerColor = defaults.eolMarkerColor
        isEolMarkersEnabled = defaults.eolMarkers
    }

    /**
     * Adds a custom highlight painter.
     *
     * @param highlight The highlight
     */
    fun addCustomHighlight(highlight: Highlight) {
        highlight.init(textArea, highlights)
        highlights = highlight
    }

    /** Interface for highlights. */
    interface Highlight {
        /** Called after the highlight painter is added. */
        fun init(textArea: JEditTextArea, next: Highlight?)

        /** This should paint the highlight and delegate to the next highlight painter. */
        fun paintHighlight(g: Graphics, line: Int, y: Int)

        /**
         * Return the tool tip to display at the specified location.
         * If this highlighter doesn't know what to display, delegate to the next highlight painter.
         */
        fun getToolTipText(e: MouseEvent): String
    }

    override fun getToolTipText(e: MouseEvent): String? =
        if (highlights != null) highlights!!.getToolTipText(e)
        else if (textArea.tokenMarker == null) null
        else textArea.getSyntaxSensitiveToolTipText(e.x, e.y)

    /** Set the font. Has the side effect of creating new font metrics and recalculating visible lines. */
    override fun setFont(font: Font) {
        super.setFont(font)
        // Toolkit.getDefaultToolkit().getFontMetrics(Font) is deprecated.
        // Use this workaround instead:
        val tempImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2d = tempImage.createGraphics()
        fontMetrics = g2d.getFontMetrics(font)
        g2d.dispose()
        textArea.recalculateVisibleLines()
    }

    /** Paint the text in the graphics view. */
    override fun paint(g: Graphics) {
        g as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        internalTabSize = fontMetrics.charWidth(' ') * tabSize

        val clipRect = g.clipBounds
        g.color = background
        g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height)

        val localHeight = fontMetrics.height
        val firstLine = textArea.firstLine
        val firstInvalid = firstLine + clipRect.y / localHeight
        val lastInvalid = firstLine + (clipRect.y + clipRect.height - 1) / localHeight

        try {
            val tokenMarker = (textArea.document as SyntaxDocument).tokenMarker
            val x = textArea.horizontalOffset
            for (line in firstInvalid..lastInvalid) g.paintLine(tokenMarker, line, x)
            if (tokenMarker != null && tokenMarker.isNextLineRequested) {
                val h = clipRect.y + clipRect.height
                repaint(0, h, width, height - h)
            }
        } catch (e: Exception) {
            System.err.println("Error repainting line range {$firstInvalid,$lastInvalid}:")
            e.printStackTrace()
        }
    }

    /** Re-paint a single line. */
    fun invalidateLine(line: Int) {
        repaint(
            0,
            textArea.lineToY(line) + fontMetrics.maxDescent + fontMetrics.leading,
            width,
            fontMetrics.height
        )
    }

    /** Re-paint a range of lines. */
    fun invalidateLineRange(firstLine: Int, lastLine: Int) {
        repaint(
            0,
            textArea.lineToY(firstLine) + fontMetrics.maxDescent + fontMetrics.leading,
            width,
            (lastLine - firstLine + 1) * fontMetrics.height
        )
    }

    /** Re-paint the selected lines. */
    fun invalidateSelectedLines() {
        invalidateLineRange(textArea.selectionStartLine, textArea.selectionEndLine)
    }

    /** Get the next tab stop after a specified point. */
    override fun nextTabStop(x: Float, tabOffset: Int): Float {
        val offset = textArea.horizontalOffset
        val nTabs = (x - offset).toInt() / internalTabSize
        return ((nTabs + 1) * internalTabSize + offset).toFloat()
    }

    /** Get the painter's preferred size. */
    override fun getPreferredSize() = Dimension(
        fontMetrics.charWidth('w') * cols,
        fontMetrics.height * rows
    )

    /** Get the painter's minimum size. */
    override fun getMinimumSize() = preferredSize

    private fun Graphics.paintLine(tokenMarker: TokenMarker?, line: Int, x: Int) {
        val defaultFont = font
        val defaultColor = foreground

        currentLineIndex = line
        val y = textArea.lineToY(line)

        if (line !in 0..<textArea.lineCount) {
            if (isPaintingInvalidLines) {
                paintHighlight(line, y)
                styles[Token.Type.INVALID.rawValue.toInt()].setGraphicsFlags(this, defaultFont)
                drawString("~", 0, y + fontMetrics.height)
            }
        } else if (tokenMarker == null) {
            paintPlainLine(line, defaultFont, defaultColor, x, y)
        } else {
            paintSyntaxLine(tokenMarker, line, defaultFont, defaultColor, x, y)
        }
    }

    private fun Graphics.paintPlainLine(line: Int, defaultFont: Font, defaultColor: Color, x: Int, y: Int) {
        var (x, y) = x to y
        paintHighlight(line, y)
        textArea.getLineText(line, currentLine)

        font = defaultFont
        color = defaultColor

        y += fontMetrics.height
        x = Utilities.drawTabbedText(currentLine, x.toFloat(), y.toFloat(), this as Graphics2D, this@TextAreaPainter, 0).toInt()

        if (isEolMarkersEnabled) {
            color = eolMarkerColor
            drawString(".", x, y)
        }
    }

    private fun Graphics.paintSyntaxLine(
        tokenMarker: TokenMarker,
        line: Int,
        defaultFont: Font,
        defaultColor: Color,
        x: Int,
        y: Int
    ) {
        var (x, y) = x to y
        textArea.getLineText(currentLineIndex, currentLine)
        currentLineTokens = tokenMarker.markTokens(currentLine, currentLineIndex)

        paintHighlight(line, y)

        font = defaultFont
        color = defaultColor

        y += fontMetrics.height
        x = this.paintSyntaxLine(
            x.toFloat() to y.toFloat(),
            currentLine,
            currentLineTokens,
            styles,
            this@TextAreaPainter
        ).toInt()

        if (isEolMarkersEnabled) {
            color = eolMarkerColor
            drawString(".", x, y)
        }
    }

    private fun Graphics.paintHighlight(line: Int, y: Int) {
        if (line in textArea.selectionStartLine..textArea.selectionEndLine)
            paintLineHighlight(line, y)
        highlights?.paintHighlight(this, line, y)
        if (isBracketHighlightingEnabled && line == textArea.bracketLine)
            paintBracketHighlight(line, y)
        if (line == textArea.caretLine)
            paintCaret(line, y)
    }

    private fun Graphics.paintLineHighlight(line: Int, y: Int) {
        var y = y

        val height = fontMetrics.height
        y += fontMetrics.leading + fontMetrics.maxDescent

        val selectionStart = textArea.selectionStart
        val selectionEnd = textArea.selectionEnd

        if (selectionStart == selectionEnd) {
            if (isLineHighlightEnabled) {
                color = lineHighlightColor
                fillRect(0, y, width, height)
            }
        } else {
            color = selectionColor

            val selectionStartLine = textArea.selectionStartLine
            val selectionEndLine = textArea.selectionEndLine
            val lineStart = textArea.getLineStartOffset(line)

            val x1: Int
            var x2: Int

            if (textArea.isSelectionRectangular) {
                val lineLen = textArea.getLineLength(line)
                x1 = textArea.fastOffsetToX(
                    line,
                    min(lineLen, selectionStart - textArea.getLineStartOffset(selectionStartLine))
                )
                x2 = textArea.fastOffsetToX(
                    line,
                    min(lineLen, selectionEnd - textArea.getLineStartOffset(selectionEndLine))
                )
                if (x1 == x2) x2++
            } else if (selectionStartLine == selectionEndLine) {
                x1 = textArea.fastOffsetToX(line, selectionStart - lineStart)
                x2 = textArea.fastOffsetToX(line, selectionEnd - lineStart)
            } else if (line == selectionEndLine) {
                x1 = 0
                x2 = textArea.fastOffsetToX(line, selectionEnd - lineStart)
            } else {
                x1 = 0
                x2 = width
            }

            // "inlined" min/max
            fillRect(min(x1, x2), y, if (x1 > x2) x1 - x2 else x2 - x1, height)
        }
    }

    private fun Graphics.paintBracketHighlight(line: Int, y: Int) {
        var y = y
        val position = textArea.bracketPosition
        if (position == -1) return
        y += fontMetrics.leading + fontMetrics.maxDescent
        val x = textArea.fastOffsetToX(line, position)
        color = bracketHighlightColor
        // Hack: since there is no fast way to get the character from the bracket matching routine, use '(' since all
        // brackets probably have the same width anyway; this is a monospaced font after all.
        drawRect(x, y, fontMetrics.charWidth('(') - 1, fontMetrics.height - 1)
    }

    private fun Graphics.paintCaret(line: Int, y: Int) {
        var y = y
        if (textArea.isCaretVisible) {
            val offset = textArea.caretPosition - textArea.getLineStartOffset(line)
            val caretX = textArea.fastOffsetToX(line, offset)
            val caretWidth = if (isBlockCaretEnabled || textArea.isOverwriteEnabled) fontMetrics.charWidth('w') else 1
            y += fontMetrics.leading + fontMetrics.maxDescent
            val height = fontMetrics.height
            color = caretColor
            if (textArea.isOverwriteEnabled) {
                fillRect(caretX, y + height - 1, caretWidth, 1)
            } else {
                drawRect(caretX, y, caretWidth, height - 1)
            }
        }
    }
}